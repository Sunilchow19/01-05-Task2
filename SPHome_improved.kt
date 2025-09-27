@OptIn(ExperimentalPagerApi::class)
@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun SPHome(navController: NavController, viewModel: SPHomeViewModel) {
    val viewModel: SPHomeViewModel = viewModel(
        factory = SPHomeViewModelFactory(UserRepository())
    )
    val context = LocalContext.current
    var isVideoVisible by remember { mutableStateOf(true) }
    var isFullScreen by remember { mutableStateOf(false) }

    // Initialize with context
    val newViewModel: SPAccountViewModel = viewModel(
        factory = SPAccountViewModelFactory(context)
    )
    
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val widthRatio = screenWidth / 412.dp
    val heightRatio = screenHeight / 917.dp
    val clipboardManager = LocalClipboardManager.current
    var isMenuVisible by remember { mutableStateOf(false) }

    val pagerState = rememberPagerState()
    val scrollState = rememberScrollState()
    val submittedServices by viewModel.submittedServices.collectAsState()

    // Video loading states - improved
    val preloadedVideos = VideoPreloader.getPreloadedVideos()
    val fullScreenVideoUrl = preloadedVideos?.first
    val shortVideoUrl = preloadedVideos?.second
    val sliderVideoUrl = preloadedVideos?.third
    
    // Enhanced video readiness tracking
    var isFullScreenVideoReady by remember { mutableStateOf(VideoPreloader.isFullScreenVideoReady()) }
    var isShortVideoReady by remember { mutableStateOf(VideoPreloader.isShortVideoReady()) }
    var isSliderVideoReady by remember { mutableStateOf(VideoPreloader.isSliderVideoReady()) }
    var isPrebuffering by remember { mutableStateOf(false) }
    
    // New loading states
    var isInitialVideoLoadComplete by remember { mutableStateOf(VideoPreloader.getInitialLoadComplete()) }
    var videoLoadingProgress by remember { mutableStateOf(VideoPreloader.getCurrentLoadingProgress()) }
    
    // Monitor video loading progress
    LaunchedEffect(Unit) {
        while (!isInitialVideoLoadComplete) {
            delay(100) // Check every 100ms
            isInitialVideoLoadComplete = VideoPreloader.getInitialLoadComplete()
            videoLoadingProgress = VideoPreloader.getCurrentLoadingProgress()
            
            // Update individual video readiness states
            isFullScreenVideoReady = VideoPreloader.isFullScreenVideoReady()
            isShortVideoReady = VideoPreloader.isShortVideoReady()
            isSliderVideoReady = VideoPreloader.isSliderVideoReady()
            
            if (isInitialVideoLoadComplete) {
                Log.d("SPHome", "Initial video loading completed")
                break
            }
        }
    }

    // Improved video pre-buffering logic
    LaunchedEffect(fullScreenVideoUrl, shortVideoUrl, sliderVideoUrl, isInitialVideoLoadComplete) {
        if (isInitialVideoLoadComplete && fullScreenVideoUrl != null && shortVideoUrl != null && sliderVideoUrl != null) {
            // Videos are ready to use
            Log.d("SPHome", "All videos are ready for use")
        } else if (fullScreenVideoUrl != null && shortVideoUrl != null && sliderVideoUrl != null && !isInitialVideoLoadComplete) {
            // URLs are available but prebuffering might still be in progress
            Log.d("SPHome", "Video URLs available, prebuffering in progress...")
            
            // Optional: Add fallback prebuffering if initial load seems stuck
            delay(5000) // Wait 5 seconds
            if (!isInitialVideoLoadComplete) {
                Log.d("SPHome", "Starting fallback prebuffering...")
                isPrebuffering = true
                
                var completedCount = 0
                val totalVideos = 3

                fun checkAllCompleted() {
                    completedCount++
                    if (completedCount == totalVideos) {
                        isPrebuffering = false
                        isInitialVideoLoadComplete = true
                    }
                }

                VideoPreloader.prebufferVideoOnDemand(fullScreenVideoUrl, VideoType.FULL_SCREEN) { success ->
                    isFullScreenVideoReady = success
                    checkAllCompleted()
                }

                VideoPreloader.prebufferVideoOnDemand(shortVideoUrl, VideoType.SHORT) { success ->
                    isShortVideoReady = success
                    checkAllCompleted()
                }

                VideoPreloader.prebufferVideoOnDemand(sliderVideoUrl, VideoType.SLIDER) { success ->
                    isSliderVideoReady = success
                    checkAllCompleted()
                }
            }
        }
    }

    // Handle full screen opening with readiness check
    LaunchedEffect(isFullScreen) {
        if (isFullScreen && !isFullScreenVideoReady && !isPrebuffering) {
            fullScreenVideoUrl?.let { url ->
                isPrebuffering = true
                VideoPreloader.prebufferVideoOnDemand(url, VideoType.FULL_SCREEN) { success ->
                    isFullScreenVideoReady = success
                    isPrebuffering = false
                }
            }
        }
    }

    // Videos availability check - improved
    val hasVideos = fullScreenVideoUrl != null && shortVideoUrl != null && sliderVideoUrl != null
    val videosReadyForDisplay = hasVideos && (isInitialVideoLoadComplete || isFullScreenVideoReady || isShortVideoReady)
    
    // Add BackHandler to close app when back button is pressed
    BackHandler {
        (context as? Activity)?.finish()
    }

    // Navigation result handling
    val currentEntry = navController.currentBackStackEntryAsState().value
    val currentDestination = navController.currentBackStackEntryAsState().value?.destination
    val currentRoute = currentDestination?.route ?: ""
    val logoutCompleted by newViewModel.logoutCompleted

    // Handle logout completion
    LaunchedEffect(logoutCompleted) {
        if (logoutCompleted) {
            navController.navigate(SPSIGNUP_NEWSCREEN) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
            newViewModel.resetLogoutState()
            isMenuVisible = false
        }
    }

    LaunchedEffect(currentEntry) {
        val submissionResult = currentEntry?.savedStateHandle?.remove<Map<String, Any>>("service_submission_result")
        submissionResult?.let {
            val serviceName = it["serviceName"] as? String
            val success = it["success"] as? Boolean ?: false
            if (serviceName != null && success) {
                viewModel.addSubmittedService(serviceName)
            }
        }
        val deleteResult = currentEntry?.savedStateHandle?.remove<Map<String, Any>>("service_delete_result")
        deleteResult?.let {
            val serviceName = it["serviceName"] as? String
            val deleted = it["deleted"] as? Boolean ?: false
            if (serviceName != null && deleted) {
                viewModel.removeSubmittedService(serviceName)
            }
        }
    }

    val navigationBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    // Calculate fixed header height (240.dp - 20.dp scrollable part)
    val fixedHeaderHeight = 100.dp + statusBarHeight

    // Calculate scroll progress and offset with smooth animation
    val scrollProgress = remember { Animatable(0f) }
    val scrollOffset = remember { Animatable(0f) }

    LaunchedEffect(scrollState.value) {
        val targetProgress = if (scrollState.value > 0) 1f else 0f
        val targetOffset = scrollState.value.toFloat().coerceAtMost(20f)

        launch {
            scrollProgress.animateTo(
                targetValue = targetProgress,
                animationSpec = tween(
                    durationMillis = 200,
                    easing = FastOutSlowInEasing
                )
            )
        }
        launch {
            scrollOffset.animateTo(
                targetValue = targetOffset,
                animationSpec = tween(
                    durationMillis = 200,
                    easing = FastOutSlowInEasing
                )
            )
        }
    }

    // Calculate scroll-based background with smooth transitions
    val scrollBasedGradient = remember(scrollProgress.value) {
        val progress = scrollProgress.value
        if (progress > 0f) {
            Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF8B9EE5),
                    Color(0xFF9BAAE9),
                )
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF5A3F90),
                    Color(0xFF7961A8),
                    Color(0xFF9984C1),
                    Color(0xFFA895CD),
                )
            )
        }
    }
    
    val imageList = listOf(
        R.drawable.sliderimg5,
        R.drawable.sliderimg6,
        R.drawable.sliderimg8,
        R.drawable.sliderimg7,
    )

    SetStatusBarColorForHome()

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        // Main scrollable content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(
                    top = fixedHeaderHeight,
                    bottom = 60.dp + navigationBarHeight
                )
        ) {
            ImageSliderforSP(
                imageList = imageList,
                pagerState = pagerState,
                widthRatio = widthRatio,
                heightRatio = heightRatio,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RectangleShape)
            )
            Spacer(modifier = Modifier.height(0.dp))

            CategoriesSection(
                navController = navController,
                submittedServices = submittedServices,
                onServiceSubmitted = { serviceName, success ->
                    if (success) {
                        viewModel.addSubmittedService(serviceName)
                    }
                }
            )
            Spacer(modifier = Modifier.height(20.dp))
            UpcomingServicesSection()
            Spacer(modifier = Modifier.height(20.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ep_footer_banner),
                    contentDescription = "Banner",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(x = 90.dp, y = -48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ep_mail_copy),
                        contentDescription = "Support Icon",
                        modifier = Modifier
                            .height(30.dp)
                            .width(120.dp)
                            .padding(top = 90.dp)
                            .clickable {
                                clipboardManager.setText(AnnotatedString("support@clanhub.in"))
                            }
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
        
        // Full screen video overlay - improved with loading state
        if(isFullScreen){
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(20f)
                    .pointerInput(Unit){}
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.8f))
                )

                Box(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .height(300.dp)
                        .fillMaxWidth()
                        .align(Alignment.Center)
                ) {
                    val imageLoader = ImageLoader.Builder(context)
                        .components {
                            if (SDK_INT >= 28) {
                                add(ImageDecoderDecoder.Factory())
                            } else {
                                add(GifDecoder.Factory())
                            }
                        }
                        .build()

                    if (videosReadyForDisplay && fullScreenVideoUrl != null) {
                        AsyncImage(
                            model = fullScreenVideoUrl,
                            contentDescription = "Full screen GIF",
                            modifier = Modifier.fillMaxSize(),
                            imageLoader = imageLoader,
                        )
                    } else {
                        // Show loading indicator for full screen video
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(48.dp)
                            )
                        }
                    }

                    Image(
                        painter = painterResource(R.drawable.full_screen),
                        contentDescription = "Full_Screen",
                        colorFilter = ColorFilter.tint(Color.White),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(vertical = 30.dp, horizontal = 12.dp)
                            .size(24.dp)
                            .clickable { isFullScreen = false }
                    )
                }
            }
        }
        
        // Short video overlay - improved with loading state
        if (isVideoVisible && !isFullScreen) {
            Box(
                modifier = Modifier
                    .padding(end = 16.dp, top = 300.dp)
                    .height(300.dp)
                    .width(200.dp)
                    .zIndex(10f)
                    .align(Alignment.CenterEnd)
            ) {
                val imageLoader = ImageLoader.Builder(context)
                    .components {
                        if (SDK_INT >= 28) {
                            add(ImageDecoderDecoder.Factory())
                        } else {
                            add(GifDecoder.Factory())
                        }
                    }
                    .build()

                if (videosReadyForDisplay && shortVideoUrl != null) {
                    AsyncImage(
                        model = shortVideoUrl,
                        contentDescription = "Short GIF",
                        modifier = Modifier.fillMaxSize(),
                        imageLoader = imageLoader,
                    )
                } else if (!isInitialVideoLoadComplete) {
                    // Show loading indicator while videos are loading
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(
                                progress = videoLoadingProgress,
                                color = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Loading...",
                                color = Color.White,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                Image(
                    painter = painterResource(R.drawable.full_screen),
                    contentDescription = "Full_Screen",
                    colorFilter = ColorFilter.tint(Color.White),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 8.dp, end = 45.dp)
                        .size(24.dp)
                        .clickable { 
                            if (videosReadyForDisplay) {
                                isFullScreen = true 
                            }
                        }
                )

                IconButton(
                    onClick = { isVideoVisible = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        // Fixed Header
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(fixedHeaderHeight)
                .background(Color.Transparent)
                .zIndex(3f)
        ) {
            Header(
                userId = "",
                navController = navController,
                onNotificationClick = { /* handle notifications */ },
                viewModel = viewModel,
                accountViewModel=newViewModel,
                scrollBasedGradient = scrollBasedGradient,
                scrollOffset = scrollOffset.value,
                modifier = Modifier.fillMaxSize(),
                onMenuClick = { isMenuVisible = true },
            )
        }

        // Footer
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(60.dp + navigationBarHeight)
                .background(Color(0xfff7f7f7))
                .zIndex(1f)
        ) {
            Footer(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(60.dp),
                currentRoute = currentRoute,
                onNavigationClick = { route ->
                    if (currentRoute != route) {
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                }
            )
        }
        
        if (isMenuVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(4f)
            ) {
                SPMenu(
                    isVisible = isMenuVisible,
                    onDismiss = { isMenuVisible = false },
                    onLogoutClick = {
                        newViewModel.logout(context)
                    },
                    onSettingsClick = { navController.navigate(SP_ACCOUNT_SETTINGS) },
                    onHelpSupportClick = { navController.navigate(SP_HELPSUPPORT) },
                    onPrivacyClick = { navController.navigate(SP_PRIVACY_POLICY) },
                    navController = navController,
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}