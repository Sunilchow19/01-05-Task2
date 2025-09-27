// Enhanced MainActivity.onCreate() with LaunchedEffect for video preloading

override fun onCreate(savedInstanceState: Bundle?) {
    installSplashScreen()
    locationPermissionManager = LocationPermissionManager(this)

    FirebaseApp.initializeApp(this)
    val firebaseAppCheck = FirebaseAppCheck.getInstance()

    firebaseAppCheck.installAppCheckProviderFactory(
        PlayIntegrityAppCheckProviderFactory.getInstance()
    )
    
    if (intent?.getBooleanExtra("NAVIGATE_TO_LOGIN", false) == true) {
        Handler(Looper.getMainLooper()).postDelayed({
            navController.navigate(LOG_IN) {
                popUpTo(navController.graph.id) { inclusive = true }
            }
        }, 100)
    }

    if (intent?.getBooleanExtra("NAVIGATE_TO_SP_LOGIN", false) == true) {
        Handler(Looper.getMainLooper()).postDelayed({
                navController.navigate(SPSIGNUP_NEWSCREEN) {
                    popUpTo(navController.graph.id) { inclusive = true }
            }
        }, 100)
    }

    // Environment setup
    FirestoreEnvironment.initializeFirebaseApps(this)
    MapsInitializer.initialize(applicationContext)

    val placesApiKey = when {
        BuildConfig.BUILD_TYPE.contains("debug", true) -> GoogleApis.DEV_GOOGLE_API_KEY
        BuildConfig.BUILD_TYPE.contains("preprod", true) -> GoogleApis.PREPROD_GOOGLE_API_KEY
        BuildConfig.BUILD_TYPE.contains("release", true) -> GoogleApis.PROD_GOOGLE_API_KEY
        else -> GoogleApis.DEV_GOOGLE_API_KEY
    }

    if (!Places.isInitialized()) {
        Places.initialize(applicationContext, placesApiKey)
    }
    
    val environment = when {
        BuildConfig.BUILD_TYPE.contains("debug", true) -> "dev"
        BuildConfig.BUILD_TYPE.contains("release", true) -> "prod"
        BuildConfig.BUILD_TYPE.contains("preprod", true) -> "preprod"
        else -> "dev"
    }

    when (environment) {
        "dev" -> FirestoreEnvironment.setEnvironment(FirestoreEnvironment.Environment.DEV)
        "preprod" -> FirestoreEnvironment.setEnvironment(FirestoreEnvironment.Environment.PREPROD)
        "prod" -> FirestoreEnvironment.setEnvironment(FirestoreEnvironment.Environment.PROD)
    }

    bookingsViewModel = ViewModelProvider(this)[BookingsViewModel::class.java]
    val repository = BookingsRepository()
    super.onCreate(savedInstanceState)
    setNavigationBarColor()
    
    var currentDeviceId: String? = null
    currentDeviceId = SessionManager.getDeviceId(this).toString()
    setupSessionListener()
    setupServiceProviderSessionListener()
    setupLogoutBroadcastReceiver()
    
    lifecycleScope.launch {
        initializeAppState()
    }
    lifecycleScope.launch {
        repository.cleanupExpiredSessions()
    }

    paymentCleanupService = PaymentCleanupService.getInstance()
    paymentCleanupService.initialize(
        paymentViewModel,
        BookingsRepository()
    )

    appStateTracker = AppStateTracker(this)
    appStateTracker.setOnAppTerminatedCallback {
        handleAppTermination()
    }

    networkMonitor = NetworkMonitor(this)

    CoroutineScope(Dispatchers.IO).launch {
        RegistrationManager.refreshRegistrationState(this@MainActivity)
    }
    
    FirebaseApp.initializeApp(this)
    razorpayManager = RazorpayManager(this)
    clearAllPreferences(this)
    
    var paymentSuccessCallback: ((String?) -> Unit)? = null
    var paymentErrorCallback: ((Int, String?) -> Unit)? = null
    
    setContent {
        ClanhubTheme {
            var showSplash by remember { mutableStateOf(true) }
            var mainContentReady by remember { mutableStateOf(false) }
            var videosFullyLoaded by remember { mutableStateOf(false) }

            // ENHANCED: LaunchedEffect to preload videos during app startup
            LaunchedEffect(Unit) {
                Log.d("MainActivity", "Starting video preloading process...")
                
                // Start video preloading immediately
                VideoPreloader.preloadVideos { fullScreenUrl, shortUrl, sliderUrl ->
                    Log.d("MainActivity", "Video URLs loaded - FullScreen: ${fullScreenUrl != null}, Short: ${shortUrl != null}, Slider: ${sliderUrl != null}")
                }
                
                // Monitor video loading progress
                var attempts = 0
                val maxAttempts = 50 // 50 attempts * 200ms = 10 seconds max wait
                
                while (!VideoPreloader.areAllVideosReady() && attempts < maxAttempts) {
                    delay(200) // Check every 200ms
                    attempts++
                    
                    val progress = VideoPreloader.getLoadingProgress()
                    val urlsLoaded = VideoPreloader.areUrlsLoaded()
                    val allReady = VideoPreloader.areAllVideosReady()
                    
                    Log.d("MainActivity", "Video loading progress: ${progress * 100}%, URLs loaded: $urlsLoaded, All ready: $allReady")
                    
                    // If URLs are loaded but videos aren't prebuffered, force prebuffer
                    if (urlsLoaded && !allReady && attempts > 15) { // After 3 seconds
                        Log.d("MainActivity", "Force prebuffering videos...")
                        VideoPreloader.forcePrebufferAllVideos()
                        break
                    }
                }
                
                videosFullyLoaded = VideoPreloader.areAllVideosReady()
                Log.d("MainActivity", "Video loading completed. All videos ready: $videosFullyLoaded")
                
                // Allow some minimum splash time for smooth UX
                delay(1000) // At least 1 second of splash
                mainContentReady = true
            }

            // ENHANCED: Wait for videos to be ready before hiding splash
            LaunchedEffect(mainContentReady, videosFullyLoaded) {
                if (mainContentReady) {
                    // Wait a bit more if videos aren't ready yet
                    if (!videosFullyLoaded) {
                        delay(1500) // Give extra time for videos
                    }
                    delay(500) // Smooth transition delay
                    showSplash = false
                }
            }

            AnimatedVisibility(
                visible = !showSplash,
                enter = fadeIn(animationSpec = tween(durationMillis = 300)),
                exit = fadeOut(animationSpec = tween(durationMillis = 300)),
                modifier = Modifier.fillMaxSize()
            ) {
                val context = LocalContext.current
                navController = rememberNavController()
                mainNavController = navController
                
                val locationViewModel: LocationViewModel = viewModel(
                    factory = LocationViewModel.LocationViewModelFactory(locationService)
                )
                val isOnline by networkMonitor.isOnline.collectAsState()
                var isRefreshing by remember { mutableStateOf(false) }
                val subscriptionrepository = SubscriptionFirebase()
                var shouldRequestLocationPermission by remember { mutableStateOf(false) }
                
                LaunchedEffect(isRefreshing) {
                    if (isRefreshing) {
                        delay(5000)
                        isRefreshing = false
                    }
                }

                RegistrationManager.addListener { registered ->
                    if (registered) {
                        CoroutineScope(Dispatchers.Main).launch {
                            navController.currentBackStackEntry?.savedStateHandle?.set(
                                "forceRefresh",
                                true
                            )

                            withContext(Dispatchers.IO) {
                                clearSelectedTime(this@MainActivity, userId)
                            }
                        }
                    }
                }

                GlobalLocationPermissionHandler(
                    navController = navController,
                    locationViewModel = locationViewModel,
                )
                
                AppNavigation(
                    navController = navController,
                    serviceProviderViewModel = initializeViewModels().serviceProviderViewModel,
                    context = context,
                    onReadyForLocationPermission = { shouldRequestLocationPermission = true }
                )

                if (!isOnline) {
                    NoInternetScreen(
                        onRetry = { isRefreshing = true },
                        isRefreshing = isRefreshing
                    )
                }
            }
            
            AnimatedVisibility(
                visible = showSplash,
                enter = fadeIn(animationSpec = tween(durationMillis = 500)),
                exit = fadeOut(animationSpec = tween(durationMillis = 300)),
                modifier = Modifier.fillMaxSize()
            ) {
                // ENHANCED: Show loading progress in splash screen
                Box(modifier = Modifier.fillMaxSize()) {
                    ClanhubLogoPage(
                        onAnimationEnd = { /* Controlled by LaunchedEffect now */ }
                    )
                    
                    // Optional: Show loading indicator
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 100.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val loadingProgress by remember { 
                            derivedStateOf { VideoPreloader.getLoadingProgress() }
                        }
                        
                        if (loadingProgress > 0f) {
                            LinearProgressIndicator(
                                progress = loadingProgress,
                                modifier = Modifier
                                    .width(200.dp)
                                    .padding(horizontal = 32.dp),
                                color = MaterialTheme.colors.primary
                            )
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            Text(
                                text = "Loading videos... ${(loadingProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.caption,
                                color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
    }
}