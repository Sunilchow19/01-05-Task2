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

    // ENHANCED: Video states with immediate initialization
    val preloadedVideos = VideoPreloader.getPreloadedVideos()
    val fullScreenVideoUrl = preloadedVideos?.first
    val shortVideoUrl = preloadedVideos?.second
    val sliderVideoUrl = preloadedVideos?.third
    
    // Enhanced video readiness tracking
    var isFullScreenVideoReady by remember { mutableStateOf(VideoPreloader.isFullScreenVideoReady()) }
    var isShortVideoReady by remember { mutableStateOf(VideoPreloader.isShortVideoReady()) }
    var isSliderVideoReady by remember { mutableStateOf(VideoPreloader.isSliderVideoReady()) }
    var areAllVideosReady by remember { mutableStateOf(VideoPreloader.areAllVideosReady()) }
    var videosAvailable by remember { mutableStateOf(VideoPreloader.isVideosAvailable()) }

    // ENHANCED: LaunchedEffect to ensure videos are ready when entering home
    LaunchedEffect(Unit) {
        Log.d("SPHome", "Checking video readiness on home screen entry...")
        
        // Continuously monitor video states
        while (!areAllVideosReady) {
            delay(100) // Check every 100ms
            
            isFullScreenVideoReady = VideoPreloader.isFullScreenVideoReady()
            isShortVideoReady = VideoPreloader.isShortVideoReady()
            isSliderVideoReady = VideoPreloader.isSliderVideoReady()
            areAllVideosReady = VideoPreloader.areAllVideosReady()
            videosAvailable = VideoPreloader.isVideosAvailable()
            
            val progress = VideoPreloader.getLoadingProgress()
            Log.d("SPHome", "Video readiness check - Progress: ${progress * 100}%, All ready: $areAllVideosReady")
            
            if (areAllVideosReady) {
                Log.d("SPHome", "All videos are ready for display!")
                break
            }
            
            // If videos are available but not prebuffered, force prebuffer
            if (videosAvailable && !areAllVideosReady) {
                Log.d("SPHome", "Videos available but not ready, force prebuffering...")
                VideoPreloader.forcePrebufferAllVideos()
                delay(1000) // Give it time to prebuffer
            }
        }
    }

    // ENHANCED: LaunchedEffect specifically for full screen video readiness
    LaunchedEffect(isFullScreen) {
        if (isFullScreen && !isFullScreenVideoReady) {
            Log.d("SPHome", "Full screen requested but video not ready, prebuffering...")
            fullScreenVideoUrl?.let { url ->
                VideoPreloader.prebufferVideoOnDemand(url, VideoType.FULL_SCREEN) { success ->
                    isFullScreenVideoReady = success
                    Log.d("SPHome", "Full screen video prebuffer result: $success")
                }
            }
        }
    }

    // Enhanced video availability checks
    val hasVideoUrls = fullScreenVideoUrl != null && shortVideoUrl != null && sliderVideoUrl != null
    val canDisplayVideos = hasVideoUrls && areAllVideosReady
    
    Log.d("SPHome", "Video display state - URLs: $hasVideoUrls, Ready: $areAllVideosReady, Can display: $canDisplayVideos")
    
    // Add BackHandler to close app when back button is pressed
    BackHandler {
        (context as? Activity)?.finish()
    }

    // Navigation and logout handling
    val currentEntry = navController.currentBackStackEntryAsState().value
    val currentDestination = navController.currentBackStackEntryAsState().value?.destination
    val currentRoute = currentDestination?.route ?: ""
    val logoutCompleted by newViewModel.logoutCompleted

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
    val fixedHeaderHeight = 100.dp + statusBarHeight

    // Scroll animation
    val scrollProgress = remember { Animatable(0f) }
    val scrollOffset = remember { Animatable(0f) }

    LaunchedEffect(scrollState.value) {
        val targetProgress = if (scrollState.value > 0) 1f else 0f
        val targetOffset = scrollState.value.toFloat().coerceAtMost(20f)

        launch {
            scrollProgress.animateTo(
                targetValue = targetProgress,
                animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)
            )
        }
        launch {
            scrollOffset.animateTo(
                targetValue = targetOffset,
                animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)
            )
        }
    }

    // Gradient background
    val scrollBasedGradient = remember(scrollProgress.value) {
        val progress = scrollProgress.value
        if (progress > 0f) {
            Brush.verticalGradient(
                colors = listOf(Color(0xFF8B9EE5), Color(0xFF9BAAE9))
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF5A3F90), Color(0xFF7961A8),
                    Color(0xFF9984C1), Color(0xFFA895CD)
                )
            )
        }
    }
    
    val imageList = listOf(
        R.drawable.sliderimg5, R.drawable.sliderimg6,
        R.drawable.sliderimg8, R.drawable.sliderimg7,
    )

    SetStatusBarColorForHome()

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
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
            
            // Footer banner
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
        
        // ENHANCED: Full screen video overlay with better loading handling
        if (isFullScreen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(20f)
                    .pointerInput(Unit) {}
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

                    if (canDisplayVideos && isFullScreenVideoReady && fullScreenVideoUrl != null) {
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
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Loading video...",
                                    color = Color.White,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }

                    Image(
                        painter = painterResource(R.drawable.full_screen),
                        contentDescription = "Close Full Screen",
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
        
        // ENHANCED: Short video overlay with better state management
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

                if (canDisplayVideos && isShortVideoReady && shortVideoUrl != null) {
                    AsyncImage(
                        model = shortVideoUrl,
                        contentDescription = "Short GIF",
                        modifier = Modifier.fillMaxSize(),
                        imageLoader = imageLoader,
                    )
                } else {
                    // Show loading state or placeholder
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!areAllVideosReady) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(
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
                }

                // Full screen button - only enabled when video is ready
                Image(
                    painter = painterResource(R.drawable.full_screen),
                    contentDescription = "Full Screen",
                    colorFilter = ColorFilter.tint(
                        if (canDisplayVideos) Color.White else Color.Gray
                    ),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 8.dp, end = 45.dp)
                        .size(24.dp)
                        .clickable(enabled = canDisplayVideos) { 
                            if (canDisplayVideos) {
                                isFullScreen = true 
                            }
                        }
                )

                // Close button
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
                accountViewModel = newViewModel,
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
        
        // Menu overlay
        if (isMenuVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(4f)
            ) {
                SPMenu(
                    isVisible = isMenuVisible,
                    onDismiss = { isMenuVisible = false },
                    onLogoutClick = { newViewModel.logout(context) },
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