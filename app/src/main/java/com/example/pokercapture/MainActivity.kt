package com.example.pokercapture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var detail: TextView
    private lateinit var latency: TextView
    private lateinit var cap: Button
    private val captureLauncher=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){ r ->
        if(r.resultCode==Activity.RESULT_OK && r.data!=null){
            startForegroundService(Intent(this,CaptureService::class.java).apply{
                action=CaptureService.ACTION_START
                putExtra(CaptureService.EXTRA_RESULT_CODE,r.resultCode)
                putExtra(CaptureService.EXTRA_DATA,r.data)
            }); status.text="READY"
        } else status.text="Permission denied"
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_main)
        status=findViewById(R.id.status); result=findViewById(R.id.result); detail=findViewById(R.id.detail)
        latency=findViewById(R.id.latency); cap=findViewById(R.id.capButton)
        val prefs=getSharedPreferences("capture",MODE_PRIVATE); val crop=findViewById<RadioGroup>(R.id.cropGroup)
        when(prefs.getString("crop_mode","LEFT")){
            "RIGHT"->findViewById<RadioButton>(R.id.cropRight).isChecked=true
            "TOP"->findViewById<RadioButton>(R.id.cropTop).isChecked=true
            "BOTTOM"->findViewById<RadioButton>(R.id.cropBottom).isChecked=true
            else->findViewById<RadioButton>(R.id.cropLeft).isChecked=true
        }
        crop.setOnCheckedChangeListener{_,id->prefs.edit().putString("crop_mode",when(id){
            R.id.cropRight->"RIGHT";R.id.cropTop->"TOP";R.id.cropBottom->"BOTTOM";else->"LEFT"}).apply()}
        findViewById<Button>(R.id.startButton).setOnClickListener{
            val mgr=getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            captureLauncher.launch(mgr.createScreenCaptureIntent())
        }
        cap.setOnClickListener{
            cap.isEnabled=false; result.text="CAPTURED"; detail.text="Saved for analysis"; latency.text=""
            sendBroadcast(Intent(CaptureService.ACTION_CAP).setPackage(packageName))
            cap.postDelayed({cap.isEnabled=true},700)
        }
        findViewById<Button>(R.id.stopButton).setOnClickListener{
            startService(Intent(this,CaptureService::class.java).apply{action=CaptureService.ACTION_STOP});status.text="STOPPED"}
    }
}