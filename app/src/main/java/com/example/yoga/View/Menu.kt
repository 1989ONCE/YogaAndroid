package com.example.yoga.View

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.hardware.camera2.CameraManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.util.Size
import android.widget.Button
import android.widget.Toast
import androidx.activity.viewModels
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.example.yoga.Model.GlobalVariable
import com.example.yoga.Model.MainHandLandmarkViewModel
import com.example.yoga.Model.MainGestureRecognizeViewModel
import com.example.yoga.Model.HandLandmarkerHelper
import com.example.yoga.Model.PoseLandmarkerHelper
import com.example.yoga.databinding.ActivityMenuBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class Menu : AppCompatActivity(), HandLandmarkerHelper.LandmarkerListener {
    private lateinit var menuBinding: ActivityMenuBinding
    private var global=GlobalVariable.getInstance()
    private lateinit var currentSelect:Button  //call by reference

    // control UI by pose
    //拿mediapipe model
    private lateinit var handLandmarkerHelper: HandLandmarkerHelper
    private val handViewModel : MainHandLandmarkViewModel by viewModels()

    //分析圖片
    private var imageAnalyzer: ImageAnalysis? = null
    //前鏡頭
    private var camera: Camera? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT

    //開個thread
    private lateinit var backgroundExecutor: ExecutorService


    // yogaMat function
    private lateinit var python : Python
    private lateinit var yogaMat : PyObject
    private var yogaMatFunctionThread: Thread? = null
    private var threadFlag : Boolean = true
    private var functionNumber: Int = 0


    // for判斷左滑/右滑 紀錄手心底的x點位
    private var previousPalmX: Float? = null
    private var firstPalmX: Float? = null
    private var swipeRightDetected = false
    private var swipeLeftDetected = false

    // 手部偵測status描述
    private var angleshowtext:String = ""

    fun lastpage(){
        threadFlag = false // to stop thread

        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("playMusic",false)
        }
        startActivity(intent)
        finish()
    }
    fun nextpage(){
        nextpage(currentSelect.text.toString())
    }
    fun nextpage(posename:String){
        threadFlag = false // to stop thread

        val intent = Intent(this, VideoGuide::class.java).apply {
            putExtra("poseName",posename)
        }
        startActivity(intent)
        finish()
    }
    fun select(){
        currentSelect.setBackgroundColor(Color.rgb(10,240,5))
        currentSelect.setTextColor(Color.rgb(60,60,0))
        currentSelect.setShadowLayer(15f,5f,5f,Color.WHITE)
    }
    fun unselect(){
        currentSelect.setBackgroundColor(Color.BLUE)
        currentSelect.setTextColor(Color.WHITE)
        currentSelect.setShadowLayer(0f,0f,0f,0)
    }
    fun selectTo(btn: Button){
        unselect()
        currentSelect = btn
        select()
    }
    fun up(){
        var next = when(currentSelect){
            menuBinding.button3 -> menuBinding.button1
            menuBinding.button4 -> menuBinding.button2
            menuBinding.button5 -> menuBinding.button3
            menuBinding.button6 -> menuBinding.button4
            menuBinding.button7 -> menuBinding.button5
            menuBinding.button8 -> menuBinding.button6
            menuBinding.button9 -> menuBinding.button7
            menuBinding.button10 -> menuBinding.button8

            else -> currentSelect
        }
        selectTo(next)
    }
    fun down(){
        var next = when(currentSelect){
            menuBinding.button1-> menuBinding.button3
            menuBinding.button2-> menuBinding.button4
            menuBinding.button3-> menuBinding.button5
            menuBinding.button4-> menuBinding.button6
            menuBinding.button5-> menuBinding.button7
            menuBinding.button6-> menuBinding.button8
            menuBinding.button7-> menuBinding.button9
            menuBinding.button8 ->menuBinding.button10
            else -> currentSelect
        }
        selectTo(next)
    }
    fun left(){
        var next = when(currentSelect){
            menuBinding.button2-> menuBinding.button1
            menuBinding.button4-> menuBinding.button3
            menuBinding.button6-> menuBinding.button5
            menuBinding.button8-> menuBinding.button7
            menuBinding.button10-> menuBinding.button9
            else -> currentSelect
        }
        selectTo(next)
    }
    fun right(){
        var next = when(currentSelect){
            menuBinding.button1-> menuBinding.button2
            menuBinding.button3-> menuBinding.button4
            menuBinding.button5-> menuBinding.button6
            menuBinding.button7-> menuBinding.button8
            menuBinding.button9-> menuBinding.button10
            else -> currentSelect
        }
        selectTo(next)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener(Runnable {
            // 获取 CameraProvider
            val cameraProvider : ProcessCameraProvider = cameraProviderFuture.get()
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraFacing = cameraManager.cameraIdList[0].toInt()

            // 配置预览
            val aspectRatio: Rational = Rational(4, 3) // 指定4:3的寬高比
            val size: Size = Size(aspectRatio.numerator, aspectRatio.denominator)

            val preview : Preview = Preview.Builder()
                .setTargetResolution(size)
                .build()
                .also {
                    it.setSurfaceProvider(menuBinding.camera.getSurfaceProvider())
                }

            // 配置相机选择器
            val cameraSelector : CameraSelector =
                CameraSelector.Builder().requireLensFacing(cameraFacing).build()

            // ImageAnalysis. Using RGBA 8888 to match how our models work
            imageAnalyzer =
                ImageAnalysis.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    //.setTargetRotation(CalibrationStageBinding.camera.display.rotation) // 模擬器需要指定旋轉
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    // The analyzer can then be assigned to the instance
                    .also {
                        it.setAnalyzer(backgroundExecutor) { image ->
                            detectPose(image)
                        }
                    }

            // 绑定相机和预览
            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview , imageAnalyzer
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun detectPose(imageProxy: ImageProxy) {
        if(this::handLandmarkerHelper.isInitialized) {
            handLandmarkerHelper.detectLiveStream(
                    imageProxy = imageProxy,
                    //isFrontCamera = cameraFacing == CameraSelector.LENS_FACING_FRONT
                    isFrontCamera = cameraFacing >= 0,
            )
        }
    }

    private fun handleSwipePoseDetection(resultBundle: HandLandmarkerHelper.ResultBundle) {

        this.runOnUiThread {

            // Pass the results to GestureOverlayView
            menuBinding.gestureOverlay.setResults(
                handLandmarkerResults = resultBundle.results.first(),
                imageHeight = menuBinding.camera.height,
                imageWidth = menuBinding.camera.width,
                runningMode = RunningMode.LIVE_STREAM
            )
        }

        // Detect swipe direction using the palm landmark (index 0)
        val palmLandmark = resultBundle.results.first().landmarks().firstOrNull()?.get(0)
        if (palmLandmark != null) {
            val currentPalmX = palmLandmark.x()

            // 當手第一次在鏡頭裡被偵測到，紀錄下它的點位
            if (firstPalmX == null) {
                firstPalmX = currentPalmX
            }


            firstPalmX?.let { initialPalmX ->
                previousPalmX?.let { prevX ->
                    Log.d("value", "Value: $currentPalmX and $prevX")
                    if (currentPalmX > initialPalmX + 0.3 && !swipeRightDetected) {
                        Log.d("SwipeDetection", "Swiping right")
                        menuBinding.angleShow.text = "右滑"
                        swipeRightDetected = true
                        if(threadFlag){
                            runOnUiThread {
                                nextpage(currentSelect.text.toString())
                            }
                        }
                    } else if (currentPalmX < initialPalmX - 0.3 && !swipeLeftDetected) {
                        Log.d("SwipeDetection", "Swiping left")
                        menuBinding.angleShow.text = "左滑"
                        swipeLeftDetected = true
                        swipeRightDetected = false
                        if(threadFlag){
                            runOnUiThread {
                                lastpage()
                            }
                        }
                        swipeLeftDetected = false
                    } else {
                        Log.d("SwipeDetection", "Not Swiping")
                        menuBinding.angleShow.text = "無偵測到滑動"
                    }
                }
                previousPalmX = currentPalmX
            }
        } else {
             // Reset firstPalmX and firstPalmY if the hand is outside of the camera
            firstPalmX = null
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        menuBinding = ActivityMenuBinding.inflate(layoutInflater)
        setContentView(menuBinding.root)
        supportActionBar?.hide() // 隐藏title bar

        currentSelect = menuBinding.button1

        menuBinding.back.setOnClickListener {
            lastpage()
        }

        backgroundExecutor = Executors.newSingleThreadExecutor()

        // 初始化 CameraX
        startCamera()
        //設定手部偵測的helper
        backgroundExecutor.execute {
            handLandmarkerHelper = HandLandmarkerHelper(
                context = this,
                runningMode = RunningMode.LIVE_STREAM,
                minHandDetectionConfidence = handViewModel.currentMinHandDetectionConfidence,
                minHandTrackingConfidence = handViewModel.currentMinHandTrackingConfidence,
                minHandPresenceConfidence = handViewModel.currentMinHandPresenceConfidence,
                currentDelegate = handViewModel.currentDelegate,
                handLandmarkerHelperListener = this
            )
        }

        menuBinding.button1.setBackgroundColor(Color.BLUE)
        menuBinding.button1.setOnClickListener {
            selectTo(menuBinding.button1)
            nextpage("Tree Style")
        }
        menuBinding.button2.setBackgroundColor(Color.BLUE)
        menuBinding.button2.setOnClickListener {
            selectTo(menuBinding.button2)
            nextpage("Warrior2 Style")
        }
        menuBinding.button3.setBackgroundColor(Color.BLUE)
        menuBinding.button3.setOnClickListener {
            selectTo(menuBinding.button3)
            nextpage("Plank")
        }
        menuBinding.button4.setBackgroundColor(Color.BLUE)
        menuBinding.button4.setOnClickListener {
            selectTo(menuBinding.button4)
            nextpage("Reverse Plank")
        }
        menuBinding.button5.setBackgroundColor(Color.BLUE)
        menuBinding.button5.setOnClickListener {
            selectTo(menuBinding.button5)
            nextpage("Child\'s pose")
        }
        menuBinding.button6.setBackgroundColor(Color.BLUE)
        menuBinding.button6.setOnClickListener {
            selectTo(menuBinding.button6)
            nextpage("Seated Forward Bend")
        }
        menuBinding.button7.setBackgroundColor(Color.BLUE)
        menuBinding.button7.setOnClickListener {
            selectTo(menuBinding.button7)
            nextpage("Low Lunge")
        }
        menuBinding.button8.setBackgroundColor(Color.BLUE)
        menuBinding.button8.setOnClickListener {
            selectTo(menuBinding.button8)
            nextpage("Downward dog")
        }
        menuBinding.button9.setBackgroundColor(Color.BLUE)
        menuBinding.button9.setOnClickListener {
            selectTo(menuBinding.button9)
            nextpage("Pyramid pose")
        }
        menuBinding.button10.setBackgroundColor(Color.BLUE)
        menuBinding.button10.setOnClickListener {
            selectTo(menuBinding.button10)
            nextpage("Bridge pose")
        }

        select()

        // python start
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        python = Python.getInstance()

        // get yogaMat python module
        yogaMat = python.getModule("heatmap")

        // using yogaMat select and nextPage
        yogaMatFunctionThread = Thread {
            try {
                Thread.sleep(1000)
                while (threadFlag) {
                    functionNumber = yogaMat.callAttr("checkFunction").toInt()
                    runOnUiThread {
                        if (functionNumber == 1) {
                            right()
                        } else if (functionNumber == 2) {
                            up()
                        } else if (functionNumber == 3) {
                            left()
                        } else if (functionNumber == 4) {
                            down()
                        }
                    }
                    if (yogaMat.callAttr("checkReturn").toBoolean()) {
                        runOnUiThread{
                            nextpage()
                        }
                        break
                    }
                    Thread.sleep(750)
                }
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
            println("!!! Menu Done !!!")
        }

        yogaMatFunctionThread?.start()

        //縮小angle show 字體
        menuBinding.angleShow.textSize = 12.0f
        menuBinding.angleShow.postDelayed(updateRunnable,200)
    }

    private val updateRunnable =  object : Runnable {
        override fun run(){
            menuBinding.angleShow.text = angleshowtext
            menuBinding.angleShow.postDelayed(this, 200)
        }
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            delay(800)
            global.backgroundMusic.play()
        }
    }
    override fun onPause() {
        super.onPause()
        global.backgroundMusic.pause()
    }

    override fun onResume() {
        super.onResume()
        global.backgroundMusic.play()
    }

    override fun onDestroy() {
        super.onDestroy()
        //關掉相機
        backgroundExecutor.shutdown()
        global.backgroundMusic.pause()
    }

    override fun onError(error: String, errorCode: Int) {
        this.runOnUiThread {
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
            if (errorCode == PoseLandmarkerHelper.GPU_ERROR) {
                handViewModel.setDelegate(0)
            }
        }
    }

    override fun onResults(resultBundle: HandLandmarkerHelper.ResultBundle) {
        handleSwipePoseDetection(resultBundle)
    }
}