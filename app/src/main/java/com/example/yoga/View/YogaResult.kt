package com.example.yoga.View

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.example.yoga.Model.GlobalVariable
import com.example.yoga.databinding.ActivityYogaResultBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class YogaResult : AppCompatActivity() {
    private lateinit var yogaResultBinding: ActivityYogaResultBinding
    private var global=GlobalVariable.getInstance()

    // yogaMat nextPage
    private lateinit var python : Python
    private lateinit var yogaMat : PyObject
    private var yogaMatThread : Thread? = null
    private var threadFlag : Boolean = true

    fun lastpage(){
        threadFlag = false // to stop thread

        val intent = Intent(this, Menu::class.java)
        startActivity(intent)
        finish()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide() // 隐藏title bar
        //初始化yogaResultBinding
        yogaResultBinding = ActivityYogaResultBinding.inflate(layoutInflater)
        setContentView(yogaResultBinding.root)

        // python start
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        python = Python.getInstance()

        // get yogaMat python module
        yogaMat = python.getModule("heatmap")

        // using yogaMat nextPage
        yogaMatThread = Thread {
            try {
                while (!yogaMat.callAttr("checkReturn").toBoolean() and threadFlag) {
                    Thread.sleep(100)
                }
                if(threadFlag){
                    runOnUiThread {
                        lastpage()
                    }
                }
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
            println("!!! YogaResult Done !!!")
        }

        yogaMatThread?.start()


        val title = intent.getStringExtra("title")
        yogaResultBinding.title.text = title
        val finishTime = intent.getDoubleExtra("finishTime",0.0)
        yogaResultBinding.time.text = "完成時間:"+finishTime.toString()+"秒"
        val score = intent.getDoubleExtra("score",100.0)
        yogaResultBinding.score.text = "分數:${"%.2f".format(score)}"

        yogaResultBinding.back.text = "Back To Menu"
        yogaResultBinding.back.setOnClickListener {
            lastpage()
        }
    }
    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            delay(800)
            global.backgroundMusic.play()
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        global.backgroundMusic.pause()
    }
    override fun onPause() {
        super.onPause()
        global.backgroundMusic.pause()
    }
    override fun onResume() {
        super.onResume()
        global.backgroundMusic.play()
    }
}