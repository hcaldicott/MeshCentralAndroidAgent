package com.meshcentral.agent

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.Camera
import androidx.camera.core.CameraFilter
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.common.InputImage
import com.karumi.dexter.Dexter
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionDeniedResponse
import com.karumi.dexter.listener.PermissionGrantedResponse
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.single.PermissionListener
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A simple [Fragment] subclass as the second destination in the navigation.
 */
@Suppress("DEPRECATION")
class ScannerFragment : Fragment(), PermissionListener {
    private lateinit var previewView: PreviewView
    private lateinit var swapCameraButton: MaterialButton
    private lateinit var statusTextView: TextView
    private lateinit var statusSpinner: ProgressBar
    private lateinit var invalidMessageTextView: TextView
    private lateinit var frameBounds: View
    private lateinit var scanLine: View
    private lateinit var successIndicator: TextView
    private lateinit var barcodeScanner: BarcodeScanner
    private lateinit var flashToggleButton: MaterialButton

    private var alert: AlertDialog? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var hasFrontCamera = false
    private var currentCamera: Camera? = null
    private var torchEnabled = false
    @Volatile
    private var processingBarcode = false
    private var invalidMessageActive = false
    private var invalidMessageRunnable: Runnable? = null
    private var successHideRunnable: Runnable? = null
    private var scanLineAnimator: ObjectAnimator? = null
    private val cameraExecutor: ExecutorService by lazy { Executors.newSingleThreadExecutor() }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.scanner_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        scannerFragment = this
        visibleScreen = 2

        previewView = view.findViewById(R.id.scanner_view)
        swapCameraButton = view.findViewById(R.id.button_swap_camera)
        statusTextView = view.findViewById(R.id.status_text)
        statusSpinner = view.findViewById(R.id.status_spinner)
        invalidMessageTextView = view.findViewById(R.id.invalid_message)
        frameBounds = view.findViewById(R.id.frame_bounds)
        scanLine = view.findViewById(R.id.scan_line)
        successIndicator = view.findViewById(R.id.success_indicator)
        flashToggleButton = view.findViewById(R.id.button_flash_toggle)
        view.findViewById<Button>(R.id.button_second).setOnClickListener {
            findNavController().navigate(R.id.action_SecondFragment_to_FirstFragment)
        }

        previewView.setOnClickListener {
            if (processingBarcode) {
                processingBarcode = false
                updateStatus()
            }
        }

        swapCameraButton.setOnClickListener { toggleCamera() }
        flashToggleButton.setOnClickListener { toggleFlash() }
        barcodeScanner = BarcodeScanning.getClient()
        frameBounds.post { startScanLineAnimation() }
        updateStatus()
    }

    override fun onDestroy() {
        alert?.dismiss()
        alert = null
        invalidMessageRunnable?.let { invalidMessageTextView.removeCallbacks(it) }
        invalidMessageRunnable = null
        successHideRunnable?.let { successIndicator.removeCallbacks(it) }
        successHideRunnable = null
        stopScanLineAnimation()
        barcodeScanner.close()
        cameraProvider?.unbindAll()
        cameraProvider = null
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        frameBounds.post { startScanLineAnimation() }
        Dexter.withContext(context)
            .withPermission(android.Manifest.permission.CAMERA)
            .withListener(this)
            .check()
    }

    override fun onPause() {
        cameraProvider?.unbindAll()
        processingBarcode = false
        invalidMessageRunnable?.let { invalidMessageTextView.removeCallbacks(it) }
        invalidMessageRunnable = null
        invalidMessageActive = false
        invalidMessageTextView.visibility = View.GONE
        successHideRunnable?.let { successIndicator.removeCallbacks(it) }
        successHideRunnable = null
        successIndicator.visibility = View.GONE
        stopScanLineAnimation()
        updateStatus()
        flashToggleButton.post { flashToggleButton.visibility = View.GONE }
        super.onPause()
    }

    override fun onPermissionGranted(p0: PermissionGrantedResponse?) {
        startCamera()
    }

    override fun onPermissionRationaleShouldBeShown(p0: PermissionRequest?, p1: PermissionToken?) {
        p1?.continuePermissionRequest()
    }

    override fun onPermissionDenied(p0: PermissionDeniedResponse?) {
        findNavController().navigate(R.id.action_SecondFragment_to_FirstFragment)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            hasFrontCamera = cameraProvider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) == true
            swapCameraButton.visibility = if (hasFrontCamera) View.VISIBLE else View.GONE
            processingBarcode = false
            updateStatus()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        val selector = resolveBestCameraSelector(provider) ?: run {
            showCameraUnavailableMessage()
            return
        }
        cameraSelector = selector

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor) { imageProxy ->
                    processImageProxy(imageProxy)
                }
            }

        provider.unbindAll()
        val boundCamera = provider.bindToLifecycle(viewLifecycleOwner, selector, preview, analysis)
        currentCamera = boundCamera
        torchEnabled = false
        val hasFlashUnit = boundCamera.cameraInfo.hasFlashUnit()
        updateFlashToggle(hasFlashUnit)
    }

    private fun toggleCamera() {
        if (!hasFrontCamera) return
        cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        processingBarcode = false
        updateStatus()
        bindCameraUseCases()
    }

    private fun processImageProxy(imageProxy: ImageProxy) {
        if (processingBarcode) {
            imageProxy.close()
        } else {
            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                imageProxy.close()
                return
            }
            val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            barcodeScanner.process(inputImage)
                .addOnSuccessListener { handleBarcodes(it) }
                .addOnFailureListener { exception -> handleMlKitFailure(exception) }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        }
    }

    private fun handleMlKitFailure(exception: Exception) {
        activity?.runOnUiThread {
            Toast.makeText(activity, "Camera error: ${exception.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleBarcodes(barcodes: List<Barcode>) {
        if (barcodes.isEmpty() || processingBarcode || invalidMessageActive) return
        var sawValue = false
        for (barcode in barcodes) {
            val rawValue = barcode.rawValue ?: continue
            sawValue = true
            if (!isMshStringValid(rawValue)) continue
            processingBarcode = true
            activity?.runOnUiThread {
                updateStatus()
                showSuccessIndicator()
                confirmServerSetup(rawValue)
            }
            return
        }
        if (sawValue) {
            showInvalidMessage()
        }
    }


    private fun showInvalidMessage() {
        if (invalidMessageActive) return
        invalidMessageActive = true
        invalidMessageTextView.post {
            invalidMessageTextView.text = getString(R.string.invalid_qrcode)
            invalidMessageTextView.visibility = View.VISIBLE
        }
        invalidMessageRunnable?.let { invalidMessageTextView.removeCallbacks(it) }
        val runnable = Runnable {
            invalidMessageActive = false
            invalidMessageTextView.post {
                invalidMessageTextView.visibility = View.GONE
            }
        }
        invalidMessageRunnable = runnable
        invalidMessageTextView.postDelayed(runnable, 2000)
    }

    private fun showSuccessIndicator() {
        successHideRunnable?.let { successIndicator.removeCallbacks(it) }
        successIndicator.apply {
            alpha = 0f
            visibility = View.VISIBLE
            animate().alpha(1f).setDuration(150).start()
        }
        val runnable = Runnable {
            successIndicator.animate().alpha(0f).setDuration(150).withEndAction {
                successIndicator.visibility = View.GONE
            }.start()
        }
        successHideRunnable = runnable
        successIndicator.postDelayed(runnable, 800)
    }

    private fun updateStatus() {
        val processing = processingBarcode
        statusTextView.post {
            val context = statusTextView.context
            if (processing) {
                statusTextView.text = context.getString(R.string.scanner_status_processing)
                statusSpinner.visibility = View.GONE
            } else {
                statusTextView.text = context.getString(R.string.scanner_status_scanning)
                statusSpinner.visibility = View.VISIBLE
            }
        }
    }

    private fun toggleFlash() {
        val camera = currentCamera ?: return
        torchEnabled = !torchEnabled
        camera.cameraControl.enableTorch(torchEnabled)
        updateFlashToggle(camera.cameraInfo.hasFlashUnit())
    }

    private fun updateFlashToggle(hasFlash: Boolean) {
        flashToggleButton.post {
            if (hasFlash) {
                flashToggleButton.text = if (torchEnabled) {
                    getString(R.string.scanner_flash_off)
                } else {
                    getString(R.string.scanner_flash_on)
                }
                flashToggleButton.visibility = View.VISIBLE
            } else {
                flashToggleButton.visibility = View.GONE
            }
        }
    }

    private fun startScanLineAnimation() {
        stopScanLineAnimation()
        frameBounds.post {
            val range = frameBounds.height - scanLine.height
            if (range <= 0) return@post
            scanLineAnimator = ObjectAnimator.ofFloat(scanLine, "translationY", 0f, range.toFloat()).apply {
                duration = 1800L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
    }

    private fun stopScanLineAnimation() {
        scanLineAnimator?.cancel()
        scanLineAnimator = null
        scanLine.translationY = 0f
    }


    private fun resolveBestCameraSelector(provider: ProcessCameraProvider): CameraSelector? {
        val selectors = listOf(
            cameraSelector,
            CameraSelector.DEFAULT_BACK_CAMERA,
            CameraSelector.DEFAULT_FRONT_CAMERA,
            CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_EXTERNAL).build()
        )
        for (selector in selectors) {
            if (provider.hasCamera(selector)) {
                return selector
            }
        }
        val fallbackSelector = CameraSelector.Builder()
            .addCameraFilter(CameraFilter { infoList ->
                if (infoList.isEmpty()) infoList
                else listOf(infoList.first())
            })
            .build()
        return if (provider.hasCamera(fallbackSelector)) fallbackSelector else null
    }

    private fun showCameraUnavailableMessage() {
        activity?.runOnUiThread {
            Toast.makeText(activity, "No compatible camera found", Toast.LENGTH_LONG).show()
        }
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
            processingBarcode = false
            updateStatus()
        }
        alert = builder.show()
    }

    fun exit() {
        findNavController().navigate(R.id.action_SecondFragment_to_FirstFragment)
    }

    fun isMshStringValid(x:String):Boolean {
        if (x.startsWith("mc://").not())  return false
        var xs = x.split(',')
        if (xs.count() < 3) return false
        if (xs[0].length < 8) return false
        if (xs[1].length < 3) return false
        if (xs[2].length < 3) return false
        if (xs[0].indexOf('.') == -1) return false
        return true
    }
}
