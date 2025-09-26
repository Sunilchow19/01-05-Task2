import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

@Composable
fun ProfileHeaderSection(
    profilePictureUri: Uri?,
    onProfilePictureChange: (Uri) -> Unit,
    modifier: Modifier = Modifier,
    navController: NavController,
) {
    var showDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val viewModel: SPViewModel = viewModel(
        factory = SPViewModel.SPViewModelFactory(
            FirebaseRepository(),
            context
        )
    )

    // Add BackHandler to intercept device back button
    BackHandler(enabled = true) {
        showDialog = true
    }
    fun clearSessionAndNavigate() {
        coroutineScope.launch {
            try {
                // Get the current phone number from preferences
                val phoneNumber = ServiceProviderPreferences.getServiceProviderPhoneNumber(context)

                if (!phoneNumber.isNullOrEmpty()) {
                    Log.d("SessionClear", "Clearing session for phone: $phoneNumber")

                    // 1. Clear session from Firebase using SessionManager
                    val success = SessionManager.removeActiveSPSession(context, phoneNumber)

                    if (success) {
                        Log.d("SessionClear", "Session cleared from Firebase successfully")
                    } else {
                        Log.w("SessionClear", "Failed to clear session from Firebase")
                    }

                    // 2. Stop session monitoring
                    SessionManager.stopSessionMonitoring()

                    // 3. Clear local preferences
                    withContext(Dispatchers.IO) {
                        ServiceProviderPreferences.clearSPDataCompletely(context)
                    }

                    Log.d("SessionClear", "Local preferences cleared")

                    // 4. Sign out from Firebase Auth
                    FirebaseAuth.getInstance().signOut()

                    Log.d("SessionClear", "Firebase Auth signed out")
                } else {
                    Log.w("SessionClear", "No phone number found in preferences")
                }

                // 5. Navigate to welcome screen
                navController.navigate("WELCOME_SCREEN") {
                    popUpTo(navController.graph.startDestinationId) { inclusive = true }
                }

            } catch (e: Exception) {
                Log.e("SessionClear", "Error clearing session: ${e.message}")

                // Even if clearing fails, navigate to welcome screen
                navController.navigate("WELCOME_SCREEN") {
                    popUpTo(navController.graph.startDestinationId) { inclusive = true }
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp) // Slightly taller to accommodate profile picture overhang
    ) {
        // Background image (replace with your own image)
        Image(
            painter = painterResource(id = R.drawable.sp_reg_form_bg), // Replace with your actual drawable
            contentDescription = "Header Background",
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)),
            contentScale = ContentScale.Crop
        )

        // "Register" title
//        Text(
//            text = "Register",
//            color = Color(0xffFFFFFF),
//            style = TextStyle(
//                fontSize = 16.sp,
//                fontWeight = FontWeight.Medium,
//                fontFamily = JostMedium
//            ),
//            modifier = Modifier
//                .align(Alignment.TopStart)
//                .padding(start = 48.dp, top = 36.dp)
//        )

        // Back button
        Image(
            painter = painterResource(id = R.drawable.go_back_button),
            contentDescription = "Back",
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 32.dp)
                .width(110.dp)
                .height(35.dp)
                .align(Alignment.TopEnd)
                .clickable { showDialog = true }
        )

        if (showDialog) {
            Dialog(
                onDismissRequest = { showDialog = false },
                properties = DialogProperties(
                    dismissOnClickOutside = true,
                    dismissOnBackPress = false // Set to false to let our BackHandler work
                )
            ) {
                UnSavedPopUp(
                    onYesClick = {
                        showDialog = false
                        clearSessionAndNavigate()
                    },
                    onNoClick = { showDialog = false }
                )
            }
        }

        // Profile picture uploader
        ProfilePictureUploader(
            profilePictureUri = profilePictureUri,
            onImageSelected = { uri ->
                onProfilePictureChange(uri)
                viewModel.setProfilePictureError(false)
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = 60.dp)
                .zIndex(1f),
            viewModel = viewModel,
            showValidationToast = { message ->
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        )
    }
}


@Composable
fun ProfilePictureUploader(
    profilePictureUri: Uri?,
    onImageSelected: (Uri) -> Unit,
    viewModel: SPViewModel,
    modifier: Modifier = Modifier,
    showValidationToast: (String) -> Unit,
) {
    val context = LocalContext.current
    var selectedImageUri by remember { mutableStateOf(profilePictureUri) }
    var hasCameraPermission by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    var showError by remember { mutableStateOf(false) }

    // Observe ViewModel states
    val submitClicked by viewModel.submitClicked.collectAsState()
    val profilePictureError by viewModel.showProfilePictureError
    val profilePictureErrorNew by viewModel.showProfilePictureErrorNew
    // Effect to show error when submit is clicked but no profile picture
    LaunchedEffect(submitClicked) {
        if (submitClicked && selectedImageUri == null) {
            viewModel.setProfilePictureError(true)
            viewModel.setSubmitClicked(false)
        }
    }

    // Reset error state when a valid image is selected
    LaunchedEffect(selectedImageUri) {
        if (selectedImageUri != null && profilePictureError) {
            viewModel.setProfilePictureError(false)
        }
    }

    LaunchedEffect(selectedImageUri) {
        if (selectedImageUri != null && profilePictureErrorNew) {
            viewModel.setProfilePictureErrorNew(false)
        }
    }

    val requestPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (isGranted) {
            // Automatically launch camera when permission is granted
            takePictureLauncher.launch(photoUri)
        }
    }

    // Check camera permission on initial load
    LaunchedEffect(Unit) {
        hasCameraPermission = ContextCompat.checkSelfPermission(
            context, 
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri: Uri? ->
            uri?.let {
                val inputStream = context.contentResolver.openInputStream(uri)
                val mimeType = context.contentResolver.getType(uri)
                val allowedMimeTypes = listOf("image/jpeg", "image/jpg", "image/png")

                if (mimeType in allowedMimeTypes) {
                    val fileSize = inputStream?.available()?.toLong() ?: 0
                    if (fileSize <= 5 * 1024 * 1024) { // 5MB in bytes
                        selectedImageUri = uri
                        onImageSelected(uri)
                        viewModel.setProfilePictureErrorNew(false)
                    } else {
                        viewModel.setProfilePictureErrorNew(true)
//                        showValidationToast("File size exceeds 5MB limit")
                    }
                } else {
                    viewModel.setProfilePictureErrorNew(true)
//                    showValidationToast("Only JPG, JPEG, PNG formats allowed")
                }
            }
        }
    )

    // Create a temporary file for camera capture
    val photoFile = remember {
        File(context.cacheDir, "temp_camera_image_${System.currentTimeMillis()}.jpg")
    }
    
    val photoUri = remember {
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            photoFile
        )
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
        onResult = { success ->
            if (success && photoFile.exists()) {
                try {
                    selectedImageUri = photoUri
                    onImageSelected(photoUri)
                    viewModel.setProfilePictureError(false)
                    viewModel.setProfilePictureErrorNew(false)
                    Log.d("CameraCapture", "Image captured and saved successfully to: $photoUri")
                } catch (e: Exception) {
                    Log.e("CameraCapture", "Error processing captured image: ${e.message}")
                    showValidationToast("Error processing captured image")
                }
            } else {
                Log.w("CameraCapture", "Camera capture failed or file doesn't exist")
                showValidationToast("Failed to capture image")
            }
        }
    )

    // Fallback: TakePicturePreview with improved bitmap handling
    val takePicturePreviewLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview(),
        onResult = { bitmap: Bitmap? ->
            bitmap?.let {
                try {
                    val uri = saveBitmapToInternalStorage(context, it)
                    uri?.let { validUri ->
                        selectedImageUri = validUri
                        onImageSelected(validUri)
                        viewModel.setProfilePictureError(false)
                        viewModel.setProfilePictureErrorNew(false)
                        Log.d("CameraCapture", "Image captured and saved successfully")
                    } ?: run {
                        Log.e("CameraCapture", "Failed to save bitmap to URI")
                        showValidationToast("Failed to save captured image")
                    }
                } catch (e: Exception) {
                    Log.e("CameraCapture", "Error processing captured image: ${e.message}")
                    showValidationToast("Error processing captured image")
                }
            } ?: run {
                Log.w("CameraCapture", "Camera capture returned null bitmap")
            }
        }
    )

    // Main layout with the profile picture
    Box(modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(170.dp), // Shared size
                contentAlignment = Alignment.Center // Center everything
            ) {
                DynamicProgressIndicator(viewModel=viewModel)
                // Progress Indicator around camera - show red border when error
                if (profilePictureError) {
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .border(
                                width = 2.dp,
                                color = Color.Red,
                                shape = CircleShape
                            )
                    )
                }

                // Circular clickable image area
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .clip(CircleShape)
                        .clickable { showDialog = true }
                ) {
                    // Background user image
                    Image(
                        painter = painterResource(id = R.drawable.user_2),
                        contentDescription = "Background Image",
                        modifier = Modifier
                            .size(110.dp)
                    )

                    // Selected image overlay
                    selectedImageUri?.let {
                        Image(
                            painter = rememberAsyncImagePainter(model = it),
                            contentDescription = "Profile Picture",
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } ?: run {
                        // Camera icon overlay
                        Image(
                            painter = painterResource(id = R.drawable.camera),
                            contentDescription = "Camera Icon",
                            modifier = Modifier
                                .align(Alignment.Center)
                                .requiredSize(30.dp)
                                .padding(bottom = 5.dp)
                        )
                    }
                }
            }

            // Optional placeholder text if image not selected
            if (selectedImageUri == null) {
                Text(
                    text = "",
                    color = Color.Black,
                    style = TextStyle(
                        fontSize = 12.sp,
                        fontFamily = JostRegular,
                        fontWeight = FontWeight(400)
                    ),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }

    // Dialog overlay - Separate from the Box layout
    if (showDialog) {
        Popup(
            alignment = Alignment.BottomEnd,
            onDismissRequest = { showDialog = false },
            properties = PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showDialog = false }

            ) {
                Box(
                    modifier = Modifier
                        .offset(
                            x = 210.dp,
                            y = 120.dp
                        )
                        .requiredWidth(180.dp)
                        .requiredHeight(80.dp)
                        .background(
                            Color(0xffF2F4FA),
                            shape = RoundedCornerShape(topEnd = 16.dp)
                        )
                        .clip(RoundedCornerShape(topEnd = 16.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { /* Prevent clicks from passing through */ }
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxSize().background(Color(0xffF2F4FA))
                    ) {
                        // Camera Option
                        androidx.compose.material3.Text(
                            text = "Camera",
                            color = Color.Black,
                            maxLines = 1,
                            style = TextStyle(fontSize = 12.sp, fontFamily = JostMedium, fontWeight = FontWeight(500)),
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showDialog = false
                                    if (hasCameraPermission) {
                                        takePictureLauncher.launch(photoUri)
                                    } else {
                                        requestPermissionLauncher.launch(Manifest.permission.CAMERA)
                                    }
                                }
                                .padding(vertical = 10.dp)
                        )

                        // Divider
//                        Divider(color = Color.Gray, thickness = 1.dp)

                        // Upload from Gallery Option
                        androidx.compose.material3.Text(
                            text = "Upload from gallery",
                            color = Color.Black,
                            maxLines = 1,
                            style = TextStyle(fontSize = 12.sp, fontFamily = JostMedium, fontWeight = FontWeight(500)),
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showDialog = false
                                    pickImageLauncher.launch("image/*")
                                }
                                .padding(vertical = 10.dp)
                        )
                    }
                }
            }
        }
    }


}

// Helper function to save bitmap to internal storage and return URI
private fun saveBitmapToInternalStorage(context: Context, bitmap: Bitmap): Uri? {
    return try {
        val filename = "camera_image_${System.currentTimeMillis()}.jpg"
        val file = File(context.cacheDir, filename)
        
        val outputStream = FileOutputStream(file)
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
        outputStream.flush()
        outputStream.close()
        
        Uri.fromFile(file)
    } catch (e: Exception) {
        Log.e("BitmapSave", "Error saving bitmap: ${e.message}")
        null
    }
}