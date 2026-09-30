package com.strideshow.panelcast.board

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.TextureView
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.RendererCommon
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/**
 * A WebRTC video sink backed by a TextureView.
 *
 * Why not the stock SurfaceViewRenderer? A SurfaceView is a hole punched in
 * the window that the compositor fills from a separate buffer. It cannot be
 * layered under ink, rotated, animated, or partially overlapped by another
 * SurfaceView with predictable z-order - all of which a floating source
 * window on a whiteboard needs. A TextureView renders into the view
 * hierarchy like any other view: it obeys translation/scale/rotation,
 * elevation and clipping, and the ink layer above it simply draws over it.
 *
 * The cost is one extra GPU composite per frame, which the newer hardware
 * this whiteboard targets absorbs easily. The original single-window app
 * kept SurfaceView for the old panels; this class is for the new one.
 *
 * Note the same lifecycle trap as SurfaceView applies in a different form:
 * the SurfaceTexture exists only while the view is attached and visible, so
 * `EglRenderer.createEglSurface` must be paired with `releaseEglSurface`
 * on every availability change or frames are silently dropped.
 */
class TextureViewRenderer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextureView(context, attrs), VideoSink, TextureView.SurfaceTextureListener {

    private val renderer = EglRenderer("PanelCast/Tex")
    private var eglContext: EglBase.Context? = null
    private var initialised = false
    private var surface: Surface? = null
    private var rendererEvents: RendererCommon.RendererEvents? = null

    /** Last known frame size, for aspect-ratio-preserving window sizing. */
    @Volatile var frameWidth = 0
        private set
    @Volatile var frameHeight = 0
        private set
    @Volatile var frameRotation = 0
        private set
    var onFrameSize: ((w: Int, h: Int) -> Unit)? = null

    init {
        surfaceTextureListener = this
        // TextureView defaults to opaque; keep it so, the window draws its own chrome.
        isOpaque = true
    }

    fun init(sharedContext: EglBase.Context, events: RendererCommon.RendererEvents?) {
        if (initialised) return
        eglContext = sharedContext
        rendererEvents = events
        renderer.init(sharedContext, EglBase.CONFIG_PLAIN, GlRectDrawer())
        // Fit inside the view, never crop: a shared slide with its edges cut off is
        // worse than letterboxing on a window the user can resize anyway.
        renderer.setLayoutAspectRatio(0f)
        initialised = true
        // Surface may already be available if init runs after attach.
        surfaceTexture?.let { onSurfaceTextureAvailable(it, width, height) }
    }

    fun setMirror(mirror: Boolean) = renderer.setMirror(mirror)

    /** Aspect-fit inside whatever size the window gives us. */
    fun setFitAspect(w: Int, h: Int) {
        if (w > 0 && h > 0) renderer.setLayoutAspectRatio(w.toFloat() / h)
    }

    override fun onFrame(frame: VideoFrame) {
        val w = frame.rotatedWidth; val h = frame.rotatedHeight
        if (w != frameWidth || h != frameHeight || frame.rotation != frameRotation) {
            frameWidth = w; frameHeight = h; frameRotation = frame.rotation
            rendererEvents?.onFrameResolutionChanged(w, h, frame.rotation)
            onFrameSize?.let { cb -> post { cb(w, h) } }
        }
        renderer.onFrame(frame)
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        if (!initialised) return
        releaseSurface()
        surface = Surface(st)
        renderer.createEglSurface(st)
        Log.i(TAG, "texture surface available ${width}x$height")
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        // EglRenderer reads the surface size on each frame; nothing to do.
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        releaseSurface()
        // Returning true lets TextureView release the SurfaceTexture itself.
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) { /* per frame; no-op */ }

    private fun releaseSurface() {
        if (!initialised) return
        // releaseEglSurface is asynchronous on the render thread; the runnable
        // fires once the surface is really gone so we can drop our reference.
        val s = surface
        surface = null
        renderer.releaseEglSurface { try { s?.release() } catch (_: Exception) {} }
    }

    /** Stop rendering and free GL resources. The view is unusable afterwards. */
    fun release() {
        if (!initialised) return
        initialised = false
        releaseSurface()
        renderer.release()
    }

    fun addFrameListener(l: EglRenderer.FrameListener, scale: Float) = renderer.addFrameListener(l, scale)
    fun removeFrameListener(l: EglRenderer.FrameListener) = renderer.removeFrameListener(l)

    /** Clear to black; used when a source disconnects so no stale frame lingers. */
    fun clearImage() = renderer.clearImage()

    companion object { private const val TAG = "PanelCast/TexView" }
}
