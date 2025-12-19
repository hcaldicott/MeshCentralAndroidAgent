package com.meshcentral.agent

import android.app.AlertDialog
import android.hardware.Camera
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.navigation.fragment.findNavController
import com.budiyev.android.codescanner.CodeScanner
import com.budiyev.android.codescanner.CodeScannerView
import com.budiyev.android.codescanner.DecodeCallback
import com.budiyev.android.codescanner.ErrorCallback
import com.karumi.dexter.Dexter
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionDeniedResponse
import com.karumi.dexter.listener.PermissionGrantedResponse
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.single.PermissionListener

/**
 * A simple [Fragment] subclass as the second destination in the navigation.
 */
class ScannerFragment : Fragment(), PermissionListener {
    private var lastToast : Toast? = null
    private lateinit var codeScanner: CodeScanner
    var alert : AlertDialog? = null
    private val logTag = "ScannerFragment"
    private val cameraCandidates = mutableListOf<Int>()
    private var currentCameraIndex = 0
    private var cameraErrorHandled = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.scanner_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        scannerFragment = this
        visibleScreen = 2

        view.findViewById<Button>(R.id.button_second).setOnClickListener {
            lastToast?.cancel()
            findNavController().navigate(R.id.action_SecondFragment_to_FirstFragment)
        }

        val scannerView = view.findViewById<CodeScannerView>(R.id.scanner_view)
        val activity = requireActivity()
        lastToast = Toast.makeText(activity, "", Toast.LENGTH_LONG)
        codeScanner = CodeScanner(activity, scannerView)
        codeScanner.decodeCallback = DecodeCallback {
            activity.runOnUiThread {
                if (isMshStringValid(it.text)) {
                    lastToast?.cancel()
                    confirmServerSetup(it.text)
                } else {
                    lastToast?.setGravity(Gravity.CENTER, 0, 300)
                    lastToast?.setText(getString(R.string.invalid_qrcode))
                    lastToast?.show()
                    startPreviewForCurrentCamera(resetIndex = false)
                }
            }
        }
        codeScanner.errorCallback = ErrorCallback { throwable ->
            Log.e(logTag, "Camera error on index $currentCameraIndex", throwable)
            activity.runOnUiThread {
                moveToNextCameraOrFail(throwable)
            }
        }
        scannerView.setOnClickListener {
            startPreviewForCurrentCamera(resetIndex = false)
        }
    }

    override fun onDestroy() {
        if (alert != null) {
            alert?.dismiss()
            alert = null
        }
        lastToast?.cancel()
        super.onDestroy()
    }

    override fun onResume() {
        Log.d(logTag, "onResume")
        super.onResume()
        cameraErrorHandled = false
        refreshCameraCandidates()
        Dexter.withContext(context)
            .withPermission(android.Manifest.permission.CAMERA)
            .withListener(this)
            .check()
        //codeScanner.startPreview()
    }

    override fun onPause() {
        codeScanner.releaseResources()
        super.onPause()
    }

    fun getServerHost(serverLink : String?) : String? {
        if (serverLink == null) return null
        var x : List<String> = serverLink.split(',')
        var serverHost = x[0]
        return serverHost.substring(5)
    }

    fun confirmServerSetup(x:String) {
        if (alert != null) {
            alert?.dismiss()
            alert = null
        }
        val builder = AlertDialog.Builder(activity)
        builder.setTitle("MeshCentral Server")
        builder.setMessage(getString(R.string.setup_message, getServerHost(x)))
        builder.setPositiveButton(android.R.string.ok) { _, _ ->
            visibleScreen = 1
            (activity as MainActivity).setMeshServerLink(x)
            findNavController().navigate(R.id.action_SecondFragment_to_FirstFragment)
        }
        builder.setNeutralButton(android.R.string.cancel) { _, _ ->
            startPreviewForCurrentCamera(resetIndex = false)
        }
        alert = builder.show()
    }

    override fun onPermissionGranted(p0: PermissionGrantedResponse?) {
        Log.d(logTag, "onPermissionGranted")
        startPreviewForCurrentCamera(resetIndex = true)
    }

    override fun onPermissionRationaleShouldBeShown(p0: PermissionRequest?, p1: PermissionToken?) {
        Log.d(logTag, "onPermissionRationaleShouldBeShown")
        p1?.continuePermissionRequest()
    }

    override fun onPermissionDenied(p0: PermissionDeniedResponse?) {
        Log.d(logTag, "onPermissionDenied")
        findNavController().navigate(R.id.action_SecondFragment_to_FirstFragment)
    }

    fun exit() {
        findNavController().navigate(R.id.action_SecondFragment_to_FirstFragment)
    }

    private fun startPreviewForCurrentCamera(resetIndex: Boolean) {
        if (!::codeScanner.isInitialized) {
            return
        }
        if (resetIndex) {
            currentCameraIndex = 0
        }
        if (cameraCandidates.isEmpty()) {
            refreshCameraCandidates()
        }
        val cameraId = cameraCandidates.getOrNull(currentCameraIndex)
        if (cameraId == null) {
            handleCameraSetupFailure(null)
            return
        }
        try {
            codeScanner.camera = cameraId
            codeScanner.startPreview()
            Log.d(logTag, "Started preview with cameraId=$cameraId (index=$currentCameraIndex)")
        } catch (ex: Exception) {
            Log.w(logTag, "Unable to start preview on cameraId=$cameraId", ex)
            moveToNextCameraOrFail(ex)
        }
    }

    private fun moveToNextCameraOrFail(error: Throwable?) {
        currentCameraIndex++
        if (currentCameraIndex < cameraCandidates.size) {
            startPreviewForCurrentCamera(resetIndex = false)
        } else {
            handleCameraSetupFailure(error)
        }
    }

    private fun handleCameraSetupFailure(error: Throwable?) {
        if (cameraErrorHandled) {
            return
        }
        cameraErrorHandled = true
        Log.e(logTag, "Unable to start any available camera", error)
        if (!isAdded) {
            return
        }
        lastToast?.cancel()
        lastToast = Toast.makeText(requireContext(), getString(R.string.scanner_no_camera_available), Toast.LENGTH_LONG)
        lastToast?.show()
        findNavController().navigate(R.id.action_SecondFragment_to_FirstFragment)
    }

    @Suppress("DEPRECATION")
    private fun refreshCameraCandidates() {
        cameraCandidates.clear()
        val back = mutableListOf<Int>()
        val front = mutableListOf<Int>()
        val others = mutableListOf<Int>()
        val count = try {
            Camera.getNumberOfCameras()
        } catch (ex: Exception) {
            Log.e(logTag, "Unable to enumerate cameras", ex)
            0
        }
        for (cameraId in 0 until count) {
            val info = Camera.CameraInfo()
            try {
                Camera.getCameraInfo(cameraId, info)
                when (info.facing) {
                    Camera.CameraInfo.CAMERA_FACING_BACK -> back.add(cameraId)
                    Camera.CameraInfo.CAMERA_FACING_FRONT -> front.add(cameraId)
                    else -> others.add(cameraId)
                }
            } catch (ex: Exception) {
                Log.w(logTag, "Unable to read camera info for id $cameraId", ex)
                others.add(cameraId)
            }
        }
        cameraCandidates.addAll(back)
        cameraCandidates.addAll(front)
        cameraCandidates.addAll(others)
        currentCameraIndex = 0
        Log.d(logTag, "Camera candidate order: $cameraCandidates")
    }

    fun isMshStringValid(x:String):Boolean {
        if (x.startsWith("mc://") == false)  return false
        var xs = x.split(',')
        if (xs.count() < 3) return false
        if (xs[0].length < 8) return false
        if (xs[1].length < 3) return false
        if (xs[2].length < 3) return false
        if (xs[0].indexOf('.') == -1) return false
        return true
    }
}
