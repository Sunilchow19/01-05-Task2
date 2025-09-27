package com.sts.clanhub.spHome.screen

import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.core.app.ApplicationProvider
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Size
import com.google.firebase.storage.Firebase
import com.google.firebase.storage.storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object VideoPreloader {
    private var preloadedVideos: Triple<String?, String?, String?>? = null
    private var isPreloading = false
    private var preloadCallbacks = mutableListOf<(String?, String?, String?) -> Unit>()

    // Pre-buffering states
    private var isFullScreenPrebuffered by mutableStateOf(false)
    private var isShortVideoPrebuffered by mutableStateOf(false)
    private var isSliderVideoPrebuffered by mutableStateOf(false)
    
    // Enhanced loading states
    private var initialLoadComplete by mutableStateOf(false)
    private var currentLoadingProgress by mutableStateOf(0f)
    private var allVideosReady by mutableStateOf(false)
    private var urlsLoaded by mutableStateOf(false)
    
    // Individual video loading states
    private var fullScreenUrlLoaded by mutableStateOf(false)
    private var shortVideoUrlLoaded by mutableStateOf(false)
    private var sliderVideoUrlLoaded by mutableStateOf(false)

    fun preloadVideos(onComplete: ((String?, String?, String?) -> Unit)? = null) {
        Log.d("VideoPreloader", "preloadVideos called")
        
        // If already preloaded, return immediately
        preloadedVideos?.let { videos ->
            Log.d("VideoPreloader", "Videos already preloaded, returning immediately")
            onComplete?.invoke(videos.first, videos.second, videos.third)
            return
        }

        // Add callback if provided
        onComplete?.let { preloadCallbacks.add(it) }

        // If already preloading, just wait
        if (isPreloading) {
            Log.d("VideoPreloader", "Already preloading, waiting...")
            return
        }

        isPreloading = true
        currentLoadingProgress = 0f
        urlsLoaded = false
        allVideosReady = false

        val storage = Firebase.storage
        val storageRef = storage.reference

        val results = mutableMapOf<String, String?>()
        var completedCount = 0
        val totalVideos = 3

        fun updateProgress() {
            currentLoadingProgress = completedCount.toFloat() / totalVideos.toFloat()
            Log.d("VideoPreloader", "Progress updated: ${currentLoadingProgress * 100}%")
        }

        fun checkAllCompleted() {
            completedCount++
            updateProgress()
            
            if (completedCount == totalVideos) {
                val fullScreenUrl = results["full_screen"]
                val shortUrl = results["short"]
                val sliderUrl = results["slider"]

                preloadedVideos = Triple(fullScreenUrl, shortUrl, sliderUrl)
                urlsLoaded = true
                isPreloading = false

                Log.d("VideoPreloader", "All URLs loaded, starting prebuffering...")

                // Start prebuffering all videos in parallel
                val prebufferJobs = mutableListOf<Job>()
                
                fullScreenUrl?.let { url ->
                    prebufferJobs.add(
                        CoroutineScope(Dispatchers.IO).launch {
                            val success = prebufferVideoInternal(url, VideoType.FULL_SCREEN)
                            Log.d("VideoPreloader", "Full screen prebuffer result: $success")
                        }
                    )
                }
                
                shortUrl?.let { url ->
                    prebufferJobs.add(
                        CoroutineScope(Dispatchers.IO).launch {
                            val success = prebufferVideoInternal(url, VideoType.SHORT)
                            Log.d("VideoPreloader", "Short video prebuffer result: $success")
                        }
                    )
                }
                
                sliderUrl?.let { url ->
                    prebufferJobs.add(
                        CoroutineScope(Dispatchers.IO).launch {
                            val success = prebufferVideoInternal(url, VideoType.SLIDER)
                            Log.d("VideoPreloader", "Slider video prebuffer result: $success")
                        }
                    )
                }

                // Wait for all prebuffering to complete
                CoroutineScope(Dispatchers.IO).launch {
                    prebufferJobs.joinAll()
                    
                    withContext(Dispatchers.Main) {
                        initialLoadComplete = true
                        allVideosReady = true
                        
                        Log.d("VideoPreloader", "All videos preloaded and pre-buffered successfully!")
                        
                        // Notify all waiting callbacks
                        preloadCallbacks.forEach { callback ->
                            callback(fullScreenUrl, shortUrl, sliderUrl)
                        }
                        preloadCallbacks.clear()
                    }
                }
            }
        }

        // Load full screen video URL
        storageRef.child("gif_videos/in_app_video_full_screen_01.gif")
            .downloadUrl
            .addOnSuccessListener { uri ->
                results["full_screen"] = uri.toString()
                fullScreenUrlLoaded = true
                Log.d("VideoPreloader", "Full screen video URL loaded: ${uri}")
                checkAllCompleted()
            }
            .addOnFailureListener { exception ->
                Log.e("VideoPreloader", "Error loading full screen video", exception)
                results["full_screen"] = null
                fullScreenUrlLoaded = true // Mark as completed even if failed
                checkAllCompleted()
            }

        // Load short video URL
        storageRef.child("gif_videos/in_app_video_short.gif")
            .downloadUrl
            .addOnSuccessListener { uri ->
                results["short"] = uri.toString()
                shortVideoUrlLoaded = true
                Log.d("VideoPreloader", "Short video URL loaded: ${uri}")
                checkAllCompleted()
            }
            .addOnFailureListener { exception ->
                Log.e("VideoPreloader", "Error loading short video", exception)
                results["short"] = null
                shortVideoUrlLoaded = true // Mark as completed even if failed
                checkAllCompleted()
            }

        // Load slider video URL
        storageRef.child("gif_videos/in_app_slider_video.gif")
            .downloadUrl
            .addOnSuccessListener { uri ->
                results["slider"] = uri.toString()
                sliderVideoUrlLoaded = true
                Log.d("VideoPreloader", "Slider video URL loaded: ${uri}")
                checkAllCompleted()
            }
            .addOnFailureListener { exception ->
                Log.e("VideoPreloader", "Error loading slider video", exception)
                results["slider"] = null
                sliderVideoUrlLoaded = true // Mark as completed even if failed
                checkAllCompleted()
            }
    }

    // Enhanced internal prebuffer function with retry logic
    private suspend fun prebufferVideoInternal(videoUrl: String, videoType: VideoType): Boolean {
        var retryCount = 0
        val maxRetries = 3
        
        while (retryCount < maxRetries) {
            try {
                Log.d("VideoPreloader", "Prebuffering ${videoType.name} video (attempt ${retryCount + 1})")
                
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
                    return true
                } else {
                    retryCount++
                    if (retryCount < maxRetries) {
                        delay(1000) // Wait 1 second before retry
                        Log.d("VideoPreloader", "Retrying ${videoType.name} video prebuffering...")
                    }
                }
            } catch (e: Exception) {
                Log.e("VideoPreloader", "Error pre-buffering ${videoType.name} video (attempt ${retryCount + 1})", e)
                retryCount++
                if (retryCount < maxRetries) {
                    delay(1000) // Wait 1 second before retry
                }
            }
        }
        
        Log.e("VideoPreloader", "Failed to prebuffer ${videoType.name} video after $maxRetries attempts")
        return false
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

    // Force prebuffer all videos (for MainActivity LaunchedEffect)
    suspend fun forcePrebufferAllVideos(): Boolean {
        Log.d("VideoPreloader", "Force prebuffering all videos...")
        
        val videos = preloadedVideos
        if (videos == null) {
            Log.d("VideoPreloader", "No videos loaded yet, cannot prebuffer")
            return false
        }

        val jobs = mutableListOf<Job>()
        
        videos.first?.let { url ->
            jobs.add(CoroutineScope(Dispatchers.IO).launch {
                prebufferVideoInternal(url, VideoType.FULL_SCREEN)
            })
        }
        
        videos.second?.let { url ->
            jobs.add(CoroutineScope(Dispatchers.IO).launch {
                prebufferVideoInternal(url, VideoType.SHORT)
            })
        }
        
        videos.third?.let { url ->
            jobs.add(CoroutineScope(Dispatchers.IO).launch {
                prebufferVideoInternal(url, VideoType.SLIDER)
            })
        }

        jobs.joinAll()
        
        val allReady = isFullScreenPrebuffered && isShortVideoPrebuffered && isSliderVideoPrebuffered
        allVideosReady = allReady
        initialLoadComplete = allReady
        
        Log.d("VideoPreloader", "Force prebuffer completed. All ready: $allReady")
        return allReady
    }

    // Public getters
    fun getPreloadedVideos(): Triple<String?, String?, String?>? = preloadedVideos
    fun isFullScreenVideoReady(): Boolean = isFullScreenPrebuffered
    fun isShortVideoReady(): Boolean = isShortVideoPrebuffered
    fun isSliderVideoReady(): Boolean = isSliderVideoPrebuffered
    fun isInitialLoadComplete(): Boolean = initialLoadComplete
    fun getLoadingProgress(): Float = currentLoadingProgress
    fun isVideosAvailable(): Boolean = preloadedVideos != null
    fun areAllVideosReady(): Boolean = allVideosReady
    fun areUrlsLoaded(): Boolean = urlsLoaded

    fun clearPreloadedVideos() {
        preloadedVideos = null
        isFullScreenPrebuffered = false
        isShortVideoPrebuffered = false
        isSliderVideoPrebuffered = false
        initialLoadComplete = false
        currentLoadingProgress = 0f
        allVideosReady = false
        urlsLoaded = false
        fullScreenUrlLoaded = false
        shortVideoUrlLoaded = false
        sliderVideoUrlLoaded = false
    }
}

enum class VideoType {
    FULL_SCREEN, SHORT, SLIDER
}