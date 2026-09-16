package kz.arkotlinreserve.renderer

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.AttributeSet
import kz.arkotlinreserve.core.Intrinsics
import kz.arkotlinreserve.core.CameraGeometry
import kz.arkotlinreserve.core.MarkerDefinition
import kz.arkotlinreserve.core.MarkerObservation
import kz.arkotlinreserve.core.PrimitiveType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ARRenderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {
    private val arRenderer = PrimitiveRenderer()

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderOnTop(true)
        preserveEGLContextOnPause = true
        setRenderer(arRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun update(observation: MarkerObservation?, marker: MarkerDefinition?, intrinsics: Intrinsics?, showAxes: Boolean) {
        arRenderer.scene.set(if (observation != null && marker != null && intrinsics != null) RenderScene(observation, marker, intrinsics, showAxes) else null)
    }

    fun rendererFps(): Double = arRenderer.fps
}

private data class RenderScene(
    val observation: MarkerObservation,
    val marker: MarkerDefinition,
    val intrinsics: Intrinsics,
    val showAxes: Boolean,
)

private class PrimitiveRenderer : GLSurfaceView.Renderer {
    val scene = AtomicReference<RenderScene?>(null)
    @Volatile var fps: Double = 0.0
        private set
    private var program = 0
    private var positionLocation = 0
    private var matrixLocation = 0
    private var colorLocation = 0
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var frames = 0
    private var fpsStarted = System.nanoTime()

    private val cube = buffer(floatArrayOf(
        -0.5f,-0.5f,0f, 0.5f,-0.5f,0f, 0.5f,0.5f,0f, -0.5f,-0.5f,0f, 0.5f,0.5f,0f, -0.5f,0.5f,0f,
        -0.5f,-0.5f,1f, 0.5f,0.5f,1f, 0.5f,-0.5f,1f, -0.5f,-0.5f,1f, -0.5f,0.5f,1f, 0.5f,0.5f,1f,
        -0.5f,-0.5f,0f, -0.5f,0.5f,0f, -0.5f,0.5f,1f, -0.5f,-0.5f,0f, -0.5f,0.5f,1f, -0.5f,-0.5f,1f,
        0.5f,-0.5f,0f, 0.5f,0.5f,1f, 0.5f,0.5f,0f, 0.5f,-0.5f,0f, 0.5f,-0.5f,1f, 0.5f,0.5f,1f,
        -0.5f,-0.5f,0f, -0.5f,-0.5f,1f, 0.5f,-0.5f,1f, -0.5f,-0.5f,0f, 0.5f,-0.5f,1f, 0.5f,-0.5f,0f,
        -0.5f,0.5f,0f, 0.5f,0.5f,1f, -0.5f,0.5f,1f, -0.5f,0.5f,0f, 0.5f,0.5f,0f, 0.5f,0.5f,1f,
    ))
    private val plane = buffer(floatArrayOf(-0.5f,-0.5f,0f, 0.5f,-0.5f,0f, 0.5f,0.5f,0f, -0.5f,-0.5f,0f, 0.5f,0.5f,0f, -0.5f,0.5f,0f))
    private val axes = buffer(floatArrayOf(0f,0f,0f,1f,0f,0f, 0f,0f,0f,0f,1f,0f, 0f,0f,0f,0f,0f,1f))

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
        matrixLocation = GLES20.glGetUniformLocation(program, "uMatrix")
        colorLocation = GLES20.glGetUniformLocation(program, "uColor")
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val current = scene.get() ?: return countFrame()
        val viewport = fittedViewport(current.intrinsics)
        GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        val projection = CameraGeometry.projectionMatrix(current.intrinsics)
        val anchor = poseMatrix(current.observation)
        val local = localMatrix(current.marker)
        val modelView = FloatArray(16)
        val mvp = FloatArray(16)
        Matrix.multiplyMM(modelView, 0, anchor, 0, local, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)
        val color = current.marker.colorArgb
        draw(
            when (current.marker.primitive) {
                PrimitiveType.CUBE -> cube
                PrimitiveType.PLANE -> plane
                PrimitiveType.AXES -> axes
            },
            if (current.marker.primitive == PrimitiveType.AXES) GLES20.GL_LINES else GLES20.GL_TRIANGLES,
            if (current.marker.primitive == PrimitiveType.CUBE) 36 else if (current.marker.primitive == PrimitiveType.PLANE) 6 else 6,
            mvp,
            floatArrayOf(((color shr 16) and 255) / 255f, ((color shr 8) and 255) / 255f, (color and 255) / 255f, ((color shr 24) and 255) / 255f),
        )
        if (current.showAxes && current.marker.primitive != PrimitiveType.AXES) draw(axes, GLES20.GL_LINES, 6, mvp, floatArrayOf(1f, 1f, 1f, 1f))
        countFrame()
    }

    private fun fittedViewport(intrinsics: Intrinsics): IntArray {
        val viewport = CameraGeometry.fitCenterViewport(intrinsics.imageWidth, intrinsics.imageHeight, viewportWidth, viewportHeight)
        return intArrayOf(viewport.x, viewport.y, viewport.width, viewport.height)
    }

    private fun poseMatrix(observation: MarkerObservation): FloatArray {
        val p = observation.pose.translation; val q = observation.pose.rotation.normalized()
        val matrix = FloatArray(16)
        Matrix.setIdentityM(matrix, 0)
        val xx=q.x*q.x; val yy=q.y*q.y; val zz=q.z*q.z; val xy=q.x*q.y; val xz=q.x*q.z; val yz=q.y*q.z; val wx=q.w*q.x; val wy=q.w*q.y; val wz=q.w*q.z
        matrix[0]=1-2*(yy+zz); matrix[1]=2*(xy+wz); matrix[2]=2*(xz-wy)
        matrix[4]=2*(xy-wz); matrix[5]=1-2*(xx+zz); matrix[6]=2*(yz+wx)
        matrix[8]=2*(xz+wy); matrix[9]=2*(yz-wx); matrix[10]=1-2*(xx+yy)
        matrix[12]=p.x; matrix[13]=p.y; matrix[14]=p.z
        return matrix
    }

    private fun localMatrix(marker: MarkerDefinition): FloatArray {
        val t = marker.localTransform
        val matrix = FloatArray(16)
        Matrix.setIdentityM(matrix, 0)
        Matrix.translateM(matrix, 0, t.translation.x, t.translation.y, t.translation.z)
        val q = t.rotation.normalized()
        val angle = Math.toDegrees((2.0 * kotlin.math.acos(q.w.coerceIn(-1f, 1f))).toDouble()).toFloat()
        val axisLength = kotlin.math.sqrt(q.x*q.x + q.y*q.y + q.z*q.z)
        if (axisLength > 1e-5f) Matrix.rotateM(matrix, 0, angle, q.x/axisLength, q.y/axisLength, q.z/axisLength)
        val size = marker.physicalSizeMeters
        Matrix.scaleM(matrix, 0, t.scale.x * size, t.scale.y * size, t.scale.z * size)
        return matrix
    }

    private fun draw(vertices: FloatBuffer, mode: Int, count: Int, matrix: FloatArray, color: FloatArray) {
        GLES20.glUseProgram(program)
        vertices.position(0)
        GLES20.glVertexAttribPointer(positionLocation, 3, GLES20.GL_FLOAT, false, 12, vertices)
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glUniformMatrix4fv(matrixLocation, 1, false, matrix, 0)
        GLES20.glUniform4fv(colorLocation, 1, color, 0)
        GLES20.glLineWidth(5f)
        GLES20.glDrawArrays(mode, 0, count)
        GLES20.glDisableVertexAttribArray(positionLocation)
    }

    private fun countFrame() {
        frames++
        val now = System.nanoTime()
        val elapsed = now - fpsStarted
        if (elapsed >= 1_000_000_000L) {
            fps = frames * 1_000_000_000.0 / elapsed
            frames = 0; fpsStarted = now
        }
    }

    private fun createProgram(vertex: String, fragment: String): Int {
        fun shader(type: Int, source: String): Int = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, source); GLES20.glCompileShader(it)
            val status = IntArray(1); GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { "Ошибка GLSL: ${GLES20.glGetShaderInfoLog(it)}" }
        }
        val vertexShader = shader(GLES20.GL_VERTEX_SHADER, vertex)
        val fragmentShader = shader(GLES20.GL_FRAGMENT_SHADER, fragment)
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader); GLES20.glAttachShader(it, fragmentShader); GLES20.glLinkProgram(it)
            GLES20.glDeleteShader(vertexShader); GLES20.glDeleteShader(fragmentShader)
        }
    }

    companion object {
        private fun buffer(values: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
        private const val VERTEX_SHADER = "attribute vec3 aPosition; uniform mat4 uMatrix; void main(){ gl_Position=uMatrix*vec4(aPosition,1.0); }"
        private const val FRAGMENT_SHADER = "precision mediump float; uniform vec4 uColor; void main(){ gl_FragColor=uColor; }"
    }
}
