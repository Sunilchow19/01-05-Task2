object VideoPreloader {
    private var preloadedVideos: Triple<String?, String?, String?>? = null
    private var isPreloading = false
    private var preloadCallbacks = mutableListOf<(String?, String?, String?) -> Unit>()

    // Pre-buffering states
    private var isFullScreenPrebuffered by mutableStateOf(false)
    private var isShortVideoPrebuffered by mutableStateOf(false)
    private var isSliderVideoPrebuffered by mutableStateOf(false)
    
    // Loading states for UI
    private var isInitialLoadComplete by mutableStateOf(false)
    private var loadingProgress by mutableStateOf(0f)

    fun preloadVideos(onComplete: ((String?, String?, String?) -> Unit)? = null) {
        // If already preloaded, return immediately
        preloadedVideos?.let { videos ->
            onComplete?.invoke(videos.first, videos.second, videos.third)
            return
        }

        // Add callback if provided
        onComplete?.let { preloadCallbacks.add(it) }

        // If already preloading, just wait
        if (isPreloading) return

        isPreloading = true
        loadingProgress = 0f

        val storage = Firebase.storage
        val storageRef = storage.reference

        val results = mutableMapOf<String, String?>()
        var completedCount = 0
        val totalVideos = 3

        fun updateProgress() {
            loadingProgress = completedCount.toFloat() / totalVideos.toFloat()
        }

        fun checkAllCompleted() {
            completedCount++
            updateProgress()
            
            if (completedCount == totalVideos) {
                val fullScreenUrl = results["full_screen"]
                val shortUrl = results["short"]
                val sliderUrl = results["slider"]

                preloadedVideos = Triple(fullScreenUrl, shortUrl, sliderUrl)
                isPreloading = false

                // Start prebuffering all videos in parallel
                val prebufferJobs = mutableListOf<Job>()
                
                fullScreenUrl?.let { url ->
                    prebufferJobs.add(
                        CoroutineScope(Dispatchers.IO).launch {
                            prebufferVideoInternal(url, VideoType.FULL_SCREEN)
                        }
                    )
                }
                
                shortUrl?.let { url ->
                    prebufferJobs.add(
                        CoroutineScope(Dispatchers.IO).launch {
                            prebufferVideoInternal(url, VideoType.SHORT)
                        }
                    )
                }
                
                sliderUrl?.let { url ->
                    prebufferJobs.add(
                        CoroutineScope(Dispatchers.IO).launch {
                            prebufferVideoInternal(url, VideoType.SLIDER)
                        }
                    )
                }

                // Wait for all prebuffering to complete
                CoroutineScope(Dispatchers.IO).launch {
                    prebufferJobs.joinAll()
                    
                    withContext(Dispatchers.Main) {
                        isInitialLoadComplete = true
                        
                        // Notify all waiting callbacks
                        preloadCallbacks.forEach { callback ->
                            callback(fullScreenUrl, shortUrl, sliderUrl)
                        }
                        preloadCallbacks.clear()
                        
                        Log.d("VideoPreloader", "All videos preloaded and pre-buffered successfully")
                    }
                }
            }
        }

        // Load full screen video URL
        storageRef.child("gif_videos/in_app_video_full_screen_01.gif")
            .downloadUrl
            .addOnSuccessListener { uri ->
                results["full_screen"] = uri.toString()
                Log.d("VideoPreloader", "Full screen video URL loaded")
                checkAllCompleted()
            }
            .addOnFailureListener { exception ->
                Log.e("VideoPreloader", "Error loading full screen video", exception)
                results["full_screen"] = null
                checkAllCompleted()
            }

        // Load short video URL
        storageRef.child("gif_videos/in_app_video_short.gif")
            .downloadUrl
            .addOnSuccessListener { uri ->
                results["short"] = uri.toString()
                Log.d("VideoPreloader", "Short video URL loaded")
                checkAllCompleted()
            }
            .addOnFailureListener { exception ->
                Log.e("VideoPreloader", "Error loading short video", exception)
                results["short"] = null
                checkAllCompleted()
            }

        // Load slider video URL
        storageRef.child("gif_videos/in_app_slider_video.gif")
            .downloadUrl
            .addOnSuccessListener { uri ->
                results["slider"] = uri.toString()
                Log.d("VideoPreloader", "Slider video URL loaded")
                checkAllCompleted()
            }
            .addOnFailureListener { exception ->
                Log.e("VideoPreloader", "Error loading slider video", exception)
                results["slider"] = null
                checkAllCompleted()
            }
    }

    // Internal prebuffer function
    private suspend fun prebufferVideoInternal(videoUrl: String, videoType: VideoType): Boolean {
        return try {
            val imageLoader = ImageLoader.Builder(ApplicationProvider.getApplicationContext())
                .components {
                    if (Build.VERSION.SDK_INT >= 28) {
                        add(ImageDecoderDecoder.Factory())
                    } else {
                        add(GifDecoder.Factory())
                    }
                }
                .build()

            val request = ImageRequest.Builder(ApplicationProvider.getApplicationContext())
                .data(videoUrl)
                .size(Size.ORIGINAL)
                .build()

            val result = imageLoader.execute(request)
            val success = result is SuccessResult
            
            if (success) {
                when (videoType) {
                    VideoType.FULL_SCREEN -> isFullScreenPrebuffered = true
                    VideoType.SHORT -> isShortVideoPrebuffered = true
                    VideoType.SLIDER -> isSliderVideoPrebuffered = true
                }
                Log.d("VideoPreloader", "${videoType.name} video pre-buffered successfully")
            }
            
            success
        } catch (e: Exception) {
            Log.e("VideoPreloader", "Error pre-buffering ${videoType.name} video", e)
            false
        }
    }

    // Single prebuffer function for all video types (kept for backward compatibility)
    private fun prebufferVideo(videoUrl: String, videoType: VideoType) {
        CoroutineScope(Dispatchers.IO).launch {
            prebufferVideoInternal(videoUrl, videoType)
        }
    }

    // Helper function to prebuffer a specific video on demand
    fun prebufferVideoOnDemand(videoUrl: String?, videoType: VideoType, onComplete: (Boolean) -> Unit = {}) {
        if (videoUrl == null) {
            onComplete(false)
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val success = prebufferVideoInternal(videoUrl, videoType)
            withContext(Dispatchers.Main) {
                onComplete(success)
            }
        }
    }

    fun getPreloadedVideos(): Triple<String?, String?, String?>? = preloadedVideos
    fun isFullScreenVideoReady(): Boolean = isFullScreenPrebuffered
    fun isShortVideoReady(): Boolean = isShortVideoPrebuffered
    fun isSliderVideoReady(): Boolean = isSliderVideoPrebuffered
    
    // New functions for UI state management
    fun isInitialLoadComplete(): Boolean = isInitialLoadComplete
    fun getLoadingProgress(): Float = loadingProgress
    fun isVideosAvailable(): Boolean = preloadedVideos != null

    fun clearPreloadedVideos() {
        preloadedVideos = null
        isFullScreenPrebuffered = false
        isShortVideoPrebuffered = false
        isSliderVideoPrebuffered = false
        isInitialLoadComplete = false
        loadingProgress = 0f
    }
}

enum class VideoType {
    FULL_SCREEN, SHORT, SLIDER
}