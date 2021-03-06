package org.ar.call.multi

import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.chad.library.adapter.base.BaseQuickAdapter
import com.google.gson.Gson
import com.gyf.immersionbar.ImmersionBar
import com.kongzue.dialog.v3.MessageDialog
import com.lzf.easyfloat.EasyFloat
import com.tuo.customview.VerificationCodeView
import com.tuo.customview.VerificationCodeView.InputCompleteListener
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.ar.call.*
import org.ar.call.R
import org.ar.call.p2p.VideoActivity
import org.ar.call.utils.RTManager
import org.ar.rtm.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class MultiCallActivity : BaseActivity() ,BaseQuickAdapter.OnItemChildClickListener,RtmChannelListener{

    private var player:MediaPlayer? =null

    private lateinit var rlInputLayout : RelativeLayout
    private lateinit var etUser : VerificationCodeView
    private lateinit var rvTag : RecyclerView
    private lateinit var tvUserId : TextView
    private lateinit var tvDelTip :TextView
    private lateinit var btnCall : Button
    private lateinit var tagAdapter:TagAdapter
    private val tagList:ArrayList<String> =ArrayList()


    private lateinit var rlWaitLayout : RelativeLayout
    private lateinit var tvCallUserArr : TextView
    private lateinit var tvCallState : TextView


    private val mineUserId = CallApp.callApp.userId

    private val mainScope = MainScope()
    private val rtmClient = RTManager.rtmClient
    private var rtmChannel: RtmChannel? = null
    private val rtmCallManager = RTManager.rtmCallManager

    private var isWaiting = false//是否在呼叫响铃页面
    private var remoteInvitation:RemoteInvitation? =null//主叫的相关对象
    private  var calledArray = arrayListOf<String>() //主叫带过来的呼叫的人的数组
    private var channelId = ""//主叫带过来的频道ID
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_multicall)
        ImmersionBar.with(this).statusBarDarkFont(false, 0.2f).keyboardEnable(false).init()
        RTManager.registerChannelEvent(this)
        tagAdapter = TagAdapter()
        tagAdapter.setNewData(tagList)
        tagAdapter.onItemChildClickListener = this

        rlInputLayout = findViewById(R.id.rl_input_layout)
        etUser = findViewById(R.id.et_user)
        rvTag = findViewById(R.id.rv_tag)
        tvDelTip = findViewById(R.id.text_tip)
        tvUserId = findViewById(R.id.tv_user)
        btnCall = findViewById(R.id.btn_call)
        tvUserId.text = "您的呼叫ID:$mineUserId"


        rlWaitLayout = findViewById(R.id.rl_wait_layout)
        tvCallUserArr = findViewById(R.id.tv_call_user_arr)
        tvCallState =findViewById(R.id.tv_state)

        rvTag.layoutManager = StaggeredGridLayoutManager(3, StaggeredGridLayoutManager.VERTICAL)
        rvTag.adapter = tagAdapter

        etUser.setInputCompleteListener(object : InputCompleteListener {
            override fun inputComplete() {
                if (etUser.inputContent.length == 4) {
                    addTag()
                }
            }

            override fun deleteContent() {
            }
        })
        //从其他页面跳过来的
        val isRecCall = intent.getBooleanExtra("RecCall", false)
        if (isRecCall) {
            remoteInvitation = RTManager.remoteInvitation
            val remoteBean = Gson().fromJson(remoteInvitation?.content, MultiUserBean::class.java)
            calledArray = remoteBean.userData as ArrayList<String>
            calledArray.remove(mineUserId)
            channelId = remoteBean.chanId
            joinRTMChannel(channelId)
            showWaitingLayout()
        }
    }
    fun addTag(){
        if (etUser.inputContent == mineUserId){
            etUser.clearInputContent()
            showTipDialog("不能呼叫自己")
            return
        }

        if (etUser.inputContent in tagList){
            showTipDialog("用户ID已存在")
            return
        }

        if (tagList.size>=6){
            etUser.clearInputContent()
            showTipDialog("最多能呼叫6人")
            return
        }
        tvDelTip.visibility=View.VISIBLE
        tagList.add(etUser.inputContent)
        tagAdapter.notifyDataSetChanged()
        etUser.clearInputContent()
        btnCall.isSelected = true
        btnCall.isEnabled = true
    }

    fun Setting(view: View) {
        startActivity(Intent(this, SettingActivity::class.java).apply {
            putExtra("ARCall", false)
        })
    }
    fun Back(view: View) {
        finish()
    }

    fun Call(view: View) {
        if (EasyFloat.appFloatIsShow()){
            toast("有通话正在进行")
            return
        }
        if (tagList.size == 0){
            toast("请输入要呼叫的ID")
            return
        }
        mainScope.launch {
          val onlineMember  = queryOnlineMember()
          if (onlineMember.size == 0){
              toast("您邀请的用户都不在线")
          }else{
              val channelId = CallApp.callApp.getChannelId()
              val params = JSONObject()
              val callArray = JSONArray()
              callArray.put(mineUserId)
              params.put("Mode", 0)
              params.put("Conference", true)
              params.put("ChanId", channelId)
              onlineMember.forEach{
                  callArray.put(it)
              }
              params.put("UserData", callArray)
              Intent(this@MultiCallActivity, MultiVideosActivity::class.java).let {
                    it.putExtra("callArray", onlineMember)
                  it.putExtra("channelId", channelId)
                  it.putExtra("content", params.toString())
                  it.putExtra("isCall", true)
                  startActivity(it)
                }
              tagList.clear()
              tagAdapter.notifyDataSetChanged()

          }

      }
    }

    suspend fun queryOnlineMember(): ArrayList<String> = suspendCoroutine{ continuation ->
        val onlineArray = arrayListOf<String>()
        rtmClient?.queryPeersOnlineStatus(tagList.toSet(), object : ResultCallback<MutableMap<String, Boolean>> {
            override fun onSuccess(var1: MutableMap<String, Boolean>?) {
                var1?.forEach {
                    if (it.value) {
                        onlineArray.add(it.key)
                    }
                }
                continuation.resume(onlineArray)
            }

            override fun onFailure(var1: ErrorInfo?) {
                continuation.resume(onlineArray)
            }

        })
    }


    fun HangUp(view: View) {
        rtmCallManager?.refuseRemoteInvitation(remoteInvitation!!, null)
        showInputLayout(true)
    }

    fun Accept(view: View) {
        rtmCallManager?.acceptRemoteInvitation(remoteInvitation!!, null)
        startActivity(Intent(this@MultiCallActivity, MultiVideosActivity::class.java).apply {
            putExtra("callArray", calledArray)
            putExtra("channelId", channelId)
            putExtra("isCall", false)
        })
        showInputLayout(false)
    }

    private fun showTipDialog(tips: String) {
        MessageDialog.show(this, "提示", tips, "确定")
    }

    private fun joinRTMChannel(channelId: String){
        rtmChannel = RTManager.createChannel(channelId)
        rtmChannel?.join(null)
    }

    /**
     * 被叫收到呼叫 展示等待的界面 并响铃
     */
    private fun showWaitingLayout(){
        startRing()
        isWaiting = true;
        rlInputLayout.visibility = View.GONE
        tvCallState.text ="收到呼叫"
        tvCallUserArr.text = calledArray.joinToString(",")
        rlWaitLayout.visibility = View.VISIBLE
    }

    /**
     * 也许是被叫拒绝 或者是主叫取消了这次呼叫 停止响铃
     */
    private fun showInputLayout(needReleaseChannel: Boolean){
        stopRing()
        isWaiting = false
        rlInputLayout.visibility = View.VISIBLE
        tvCallState.text =""
        tvCallUserArr.text = ""
        rlWaitLayout.visibility = View.GONE
        if (needReleaseChannel) {
            RTManager.releaseChannel()
        }

    }

    override fun onItemChildClick(adapter: BaseQuickAdapter<*, *>?, view: View?, position: Int) {
        tagList.removeAt(position)
        tagAdapter.notifyItemRemoved(position)
        if (tagList.size == 0){
            tvDelTip.visibility = View.GONE
            btnCall.isSelected = false
            btnCall.isEnabled = false
        }
    }

    fun toast(tip: String){
        Toast.makeText(this, tip, Toast.LENGTH_SHORT).show()
    }

    //返回给被叫：收到呼叫
    override fun onRemoteInvitationReceived(remote: RemoteInvitation?) {
        super.onRemoteInvitationReceived(remote)
        runOnUiThread {
            if (isWaiting){
                val param = JSONObject().apply {
                    put("Cmd", "Calling")
                }
                remote?.response = param as String
                remote?.let {
                    rtmCallManager?.refuseRemoteInvitation(it, null)
                }
            }else{
                if(JSONObject(remote?.content).getBoolean("Conference")){
                    remoteInvitation = remote
                    val remoteBean = Gson().fromJson(remote?.content, MultiUserBean::class.java)
                    calledArray = remoteBean.userData as ArrayList<String>
                    calledArray.remove(mineUserId)
                    channelId = remoteBean.chanId
                    joinRTMChannel(channelId)
                    showWaitingLayout()
                }else{
                    val i = Intent(this@MultiCallActivity, VideoActivity::class.java)
                    i.putExtra("RecCall", true)
                    startActivity(i)
                    finish()
                }
            }
        }
    }

    override fun onRemoteInvitationCanceled(var1: RemoteInvitation?) {
        super.onRemoteInvitationCanceled(var1)
        runOnUiThread {
                calledArray.remove(var1?.callerId)
                if (calledArray.size==0){
                    toast("主叫已取消呼叫")
                    showInputLayout(true)
                }
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        RTManager.unRegisterChannelEvent(this)
        mainScope.cancel()
    }

    override fun onMemberCountUpdated(var1: Int) {
    }

    override fun onAttributesUpdated(var1: MutableList<RtmChannelAttribute>?) {
    }

    override fun onMessageReceived(var1: RtmMessage?, var2: RtmChannelMember?) {
    }

    override fun onMemberJoined(var1: RtmChannelMember?) {
        runOnUiThread {
            if (isWaiting){
                if (var1?.userId !in calledArray){
                    calledArray.add(var1?.userId.toString())
                }
            }
        }
    }

    override fun onMemberLeft(var1: RtmChannelMember?) {
        runOnUiThread {
            if (isWaiting){
                if (var1?.userId in calledArray){
                    calledArray.remove(var1?.userId)
                }
                if (isWaiting){
                    if (calledArray.size==0){//only self
                        toast("呼叫已结束")
                        showInputLayout(true)
                    }
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
       if (keyCode == KeyEvent.KEYCODE_BACK){
           if (isWaiting){
               rtmCallManager?.refuseRemoteInvitation(remoteInvitation!!, null)
               showInputLayout(true)
           }else{
               finish()
           }
           return true
       }
        return super.onKeyDown(keyCode, event)
    }

    private fun startRing() {
        try {
            if (player == null) {
                player = MediaPlayer.create(this, R.raw.video_request)
                player!!.isLooping=true
                player!!.start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 停止播放铃音
     */
    private fun stopRing() {
        try {
            if (player != null) {
                player!!.stop()
                player!!.release()
                player = null
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}