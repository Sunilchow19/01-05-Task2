// In MainActivity.onCreate() method - improved version

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
    
    // IMPROVED: Start video preloading early with callback
    Log.d("MainActivity", "Starting video preloading...")
    VideoPreloader.preloadVideos { fullScreenUrl, shortUrl, sliderUrl ->
        Log.d("MainActivity", "Video preloading completed: " +
            "FullScreen=${fullScreenUrl != null}, " +
            "Short=${shortUrl != null}, " +
            "Slider=${sliderUrl != null}")
    }
    
    razorpayManager = RazorpayManager(this)
    clearAllPreferences(this)
    
    var paymentSuccessCallback: ((String?) -> Unit)? = null
    var paymentErrorCallback: ((Int, String?) -> Unit)? = null
    
    setContent {
        ClanhubTheme {
            var showSplash by remember { mutableStateOf(true) }
            var mainContentReady by remember { mutableStateOf(false) }

            // IMPROVED: Increased splash duration to allow video preloading
            LaunchedEffect(Unit) {
                delay(3500) // Increased from 2500 to 3500 to allow more time for video loading
                mainContentReady = true
            }

            AnimatedVisibility(
                visible = !showSplash,
                enter = fadeIn(animationSpec = tween(durationMillis = 2)),
                exit = fadeOut(animationSpec = tween(durationMillis = 2)),
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
                ClanhubLogoPage(
                    onAnimationEnd = { showSplash = false }
                )
            }
        }
    }
}