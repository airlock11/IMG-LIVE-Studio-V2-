package com.imgofficial.livestudio.v2;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.SystemClock;
import android.view.Surface;
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender;
import com.pedro.encoder.utils.gl.GlUtil;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GPU compositor: samples live frame from RootEncoder's previous FBO and
 * decoded replay from a separate external OES SurfaceTexture. Insert this
 * BEFORE the scoreboard filter; scoreboard always remains outside zoom.
 * The native camera and live audio encoder are never switched or stopped.
 */
public final class V2ReplayFilter extends BaseFilterRender {
  public static final int NONE=0, FULL=1, DIAGONAL=2, CORNER=3;
  private final float[] vertices={
      -1f,-1f,0f,0f,0f,  1f,-1f,0f,1f,0f,
      -1f, 1f,0f,0f,1f,  1f, 1f,0f,1f,1f
  };
  private static final String VERTEX=
      "attribute vec4 aPosition; attribute vec2 aTextureCoord;"+
      "uniform mat4 uMVPMatrix;uniform mat4 uSTMatrix;varying vec2 vUV;"+
      "void main(){gl_Position=uMVPMatrix*aPosition;"+
      "vUV=(uSTMatrix*vec4(aTextureCoord,0.,1.)).xy;}";
  private static final String FRAGMENT=
      "#extension GL_OES_EGL_image_external : require\n"+
      "precision mediump float;varying vec2 vUV;"+
      "uniform sampler2D uLive;uniform samplerExternalOES uReplay;"+
      "uniform mat4 uReplayMatrix;uniform float uMode;uniform float uBlend;"+
      "uniform float uFullProgress;uniform float uFullOpacity;"+
      "uniform float uZoom;uniform vec2 uFocus;"+
      "vec4 rep(vec2 uv){"+
      "vec2 z=(uv-uFocus)/max(1.,uZoom)+uFocus;"+
      "z=clamp(z,vec2(0.001),vec2(0.999));"+
      "vec2 corrected=(uReplayMatrix*vec4(z,0.,1.)).xy;"+
      "return texture2D(uReplay,corrected);"+
      "}"+
      "bool inRect(vec2 uv,vec4 rect){"+
      "return uv.x>=rect.x&&uv.x<=rect.z&&uv.y>=rect.y&&uv.y<=rect.w;"+
      "}"+
      "vec2 inside(vec2 uv,vec4 rect){return (uv-rect.xy)/(rect.zw-rect.xy);}"+
      "void main(){"+
      "vec4 live=texture2D(uLive,vUV);"+
      "if(uMode<0.5||uBlend<=0.0){gl_FragColor=live;return;}"+
      "if(uMode<1.5){"+
      "vec4 full=mix(vec4(.665,.035,.965,.335),vec4(0.,0.,1.,1.),uFullProgress);"+
      "gl_FragColor=inRect(vUV,full)?mix(live,rep(inside(vUV,full)),uFullOpacity):live;return;}"+
      "if(uMode<2.5){"+
      // Final view is two same-sized nonoverlapping complete rectangles.
      "vec4 a=mix(vec4(0.,0.,1.,1.),vec4(.025,.525,.475,.975),uBlend);"+
      "vec4 b=vec4(.525,.025,.975,.475);"+
      "vec4 outc=vec4(.02,.045,.04,1.);"+
      "if(inRect(vUV,a))outc=texture2D(uLive,inside(vUV,a));"+
      "if(inRect(vUV,b))outc=rep(inside(vUV,b));"+
      "gl_FragColor=outc;return;"+
      "}"+
      // Small replay in bottom-right while live remains full frame.
      "vec4 b=mix(vec4(.965,.035,.966,.036),vec4(.665,.035,.965,.335),uBlend);"+
      "if(inRect(vUV,b))gl_FragColor=rep(inside(vUV,b));"+
      "else gl_FragColor=live;"+
      "}";
  private int program,locationPosition,locationUv,locationMvp,locationSt;
  private int locationLive,locationReplay,locationReplayMatrix;
  private int locationMode,locationBlend,locationZoom,locationFocus,locationFullProgress,locationFullOpacity;
  private int replayTex=0;
  private SurfaceTexture replayTexture;
  private Surface decoderSurface;
  private final AtomicBoolean freshFrame=new AtomicBoolean(false);
  private final float[] replayMatrix=new float[16];
  private final Object owner=new Object();
  private volatile int mode=NONE, priorMode=NONE;
  private volatile boolean leaving=false;
  private volatile long changeAt=0L;
  private volatile float zoom=1f, focusX=.5f, focusY=.5f;
  private volatile boolean hasFrame=false;

  public V2ReplayFilter(){
    squareVertex=ByteBuffer.allocateDirect(vertices.length*4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer();
    squareVertex.put(vertices).position(0);
    Matrix.setIdentityM(MVPMatrix,0);
    Matrix.setIdentityM(STMatrix,0);
    Matrix.setIdentityM(replayMatrix,0);
  }
  @Override protected void initGlFilter(Context context){
    program=GlUtil.createProgram(VERTEX,FRAGMENT);
    locationPosition=GLES20.glGetAttribLocation(program,"aPosition");
    locationUv=GLES20.glGetAttribLocation(program,"aTextureCoord");
    locationMvp=GLES20.glGetUniformLocation(program,"uMVPMatrix");
    locationSt=GLES20.glGetUniformLocation(program,"uSTMatrix");
    locationLive=GLES20.glGetUniformLocation(program,"uLive");
    locationReplay=GLES20.glGetUniformLocation(program,"uReplay");
    locationReplayMatrix=GLES20.glGetUniformLocation(program,"uReplayMatrix");
    locationMode=GLES20.glGetUniformLocation(program,"uMode");
    locationFullProgress=GLES20.glGetUniformLocation(program,"uFullProgress");
    locationFullOpacity=GLES20.glGetUniformLocation(program,"uFullOpacity");
    locationBlend=GLES20.glGetUniformLocation(program,"uBlend");
    locationZoom=GLES20.glGetUniformLocation(program,"uZoom");
    locationFocus=GLES20.glGetUniformLocation(program,"uFocus");
    int[] arr=new int[1];
    GLES20.glGenTextures(1,arr,0);replayTex=arr[0];
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,replayTex);
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
    replayTexture=new SurfaceTexture(replayTex);
    replayTexture.setOnFrameAvailableListener(t->freshFrame.set(true));
    synchronized(owner){ decoderSurface=new Surface(replayTexture);owner.notifyAll(); }
  }
  public Surface decoderSurface(long waitMillis){
    long end=SystemClock.uptimeMillis()+waitMillis;
    synchronized(owner){
      while(decoderSurface==null&&SystemClock.uptimeMillis()<end){
        try{owner.wait(Math.max(1,end-SystemClock.uptimeMillis()));}
        catch(InterruptedException ignored){Thread.currentThread().interrupt();break;}
      }
      return decoderSurface;
    }
  }
  public void enter(int selectedMode){
    if(selectedMode<FULL||selectedMode>CORNER)return;
    mode=selectedMode;priorMode=selectedMode;leaving=false;
    changeAt=SystemClock.uptimeMillis();
    zoom=1f;focusX=.5f;focusY=.5f;hasFrame=false;
  }
  public void exit(){
    if(mode==NONE||leaving)return;
    leaving=true;changeAt=SystemClock.uptimeMillis();
    // Preserve the selected replay frame and zoom until GPU exit completes.
  }
  public boolean isFull(){return mode==FULL&&!leaving;}
  public boolean hasDecodedFrame(){return hasFrame&&mode!=NONE&&!leaving;}
  public boolean isActive(){return mode!=NONE;}
  public void resetZoom(){zoom=1f;focusX=.5f;focusY=.5f;}
  public void setZoom(float amount,float x,float y){
    if(!isFull())return;
    zoom=Math.max(1f,Math.min(4f,amount));
    focusX=Math.max(0f,Math.min(1f,x));
    focusY=Math.max(0f,Math.min(1f,y));
  }
  @Override protected void drawFilter(){
    try{
      if(freshFrame.compareAndSet(true,false)&&replayTexture!=null){
        replayTexture.updateTexImage();
        replayTexture.getTransformMatrix(replayMatrix);
        // Begin the 500ms entry fade ONLY after a decoded replay frame
        // is available. This prevents a late-decoder sudden on-air cut.
        if (!hasFrame && mode!=NONE && !leaving)
          changeAt=SystemClock.uptimeMillis();
        hasFrame=true;
      }
    }catch(Exception ignored){}
    GLES20.glUseProgram(program);
    squareVertex.position(SQUARE_VERTEX_DATA_POS_OFFSET);
    GLES20.glVertexAttribPointer(locationPosition,3,GLES20.GL_FLOAT,false,
        SQUARE_VERTEX_DATA_STRIDE_BYTES,squareVertex);
    GLES20.glEnableVertexAttribArray(locationPosition);
    squareVertex.position(SQUARE_VERTEX_DATA_UV_OFFSET);
    GLES20.glVertexAttribPointer(locationUv,2,GLES20.GL_FLOAT,false,
        SQUARE_VERTEX_DATA_STRIDE_BYTES,squareVertex);
    GLES20.glEnableVertexAttribArray(locationUv);
    GLES20.glUniformMatrix4fv(locationMvp,1,false,MVPMatrix,0);
    GLES20.glUniformMatrix4fv(locationSt,1,false,STMatrix,0);
    GLES20.glUniformMatrix4fv(locationReplayMatrix,1,false,replayMatrix,0);
    float elapsedMs=Math.max(0f,(float)(SystemClock.uptimeMillis()-changeAt));
    float elapsed=Math.min(1f,elapsedMs/500f);
    float easing=elapsed*elapsed*(3f-2f*elapsed);
    float fullProgress=1f,fullOpacity=1f;
    if(mode==FULL){
      if(leaving){
        float shrink=Math.min(1f,elapsedMs/700f);
        fullProgress=1f-shrink*shrink*(3f-2f*shrink);
        float fade=Math.max(0f,Math.min(1f,(elapsedMs-700f)/150f));
        fullOpacity=1f-fade*fade*(3f-2f*fade);
      }else{
        float expand=Math.min(1f,elapsedMs/850f);
        fullProgress=expand*expand*(3f-2f*expand);
      }
    }
    if(leaving&&elapsedMs>=(mode==FULL?850f:500f)){
      mode=NONE;leaving=false;resetZoom();
    }
    float blend=!hasFrame||mode==NONE?0f:mode==FULL?1f:leaving?1f-easing:easing;
    GLES20.glUniform1f(locationMode,mode);
    GLES20.glUniform1f(locationBlend,blend);
    GLES20.glUniform1f(locationZoom,zoom);
    GLES20.glUniform2f(locationFocus,focusX,focusY);
    GLES20.glUniform1f(locationFullProgress,fullProgress);
    GLES20.glUniform1f(locationFullOpacity,hasFrame&&mode!=NONE?fullOpacity:0f);
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,previousTexId);
    GLES20.glUniform1i(locationLive,0);
    GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,replayTex);
    GLES20.glUniform1i(locationReplay,1);
  }
  @Override protected void disableResources(){
    GLES20.glDisableVertexAttribArray(locationPosition);
    GLES20.glDisableVertexAttribArray(locationUv);
    GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,0);
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,0);
  }
  @Override public void release(){
    synchronized(owner){
      if(decoderSurface!=null)decoderSurface.release();
      decoderSurface=null;
    }
    if(replayTexture!=null)replayTexture.release();
    replayTexture=null;
    if(replayTex!=0)GLES20.glDeleteTextures(1,new int[]{replayTex},0);
    if(program!=0)GLES20.glDeleteProgram(program);
  }
}
