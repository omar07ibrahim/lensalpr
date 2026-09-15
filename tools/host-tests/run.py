#!/usr/bin/env python3
"""Compile actual production sources with transparent platform doubles. No Android/SDK execution.
Requires Python 3.9+, a JDK, and kotlinc on PATH. No downloads and no live network calls.
Pass --repo /path/to/checkout to run the same assertions against an original checkout.
"""
from __future__ import annotations
import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile

STUBS = {
"Manifest.kt": '''package android
object Manifest { object permission {
 const val CAMERA="camera"; const val ACCESS_FINE_LOCATION="fine"; const val ACCESS_COARSE_LOCATION="coarse"
} }
''',
"Os.kt": '''package android.os
object SystemClock { var now=0L; fun elapsedRealtime()=now; fun elapsedRealtimeNanos()=System.nanoTime() }
class Looper { companion object { fun getMainLooper()=Looper() } }
class Handler(looper: Looper) {
 private data class Pending(val owner: Handler, val task: Runnable, val due: Long)
 fun post(r: Runnable): Boolean = postDelayed(r,0)
 fun postDelayed(r: Runnable, ms: Long): Boolean { pending.add(Pending(this,r,SystemClock.now+ms));return true }
 fun removeCallbacks(r: Runnable) { pending.removeAll { it.owner===this && it.task===r } }
 companion object {
  private val pending=mutableListOf<Pending>()
  fun reset() { pending.clear();SystemClock.now=0 }
  fun pendingCount()=pending.size
  fun advanceBy(ms: Long) {
   val target=SystemClock.now+ms
   while(true) {
    val next=pending.filter { it.due<=target }.minByOrNull { it.due } ?: break
    pending.remove(next);SystemClock.now=next.due;next.task.run()
   }
   SystemClock.now=target
  }
 }
}
interface IBinder
object Build { object VERSION { var SDK_INT=36 }; object VERSION_CODES { const val UPSIDE_DOWN_CAKE=34 } }
class PowerManager {
 var latest: WakeLock?=null
 class WakeLock { var isHeld=false;fun setReferenceCounted(v: Boolean) {};fun acquire() { isHeld=true };fun release() { isHeld=false } }
 fun newWakeLock(level: Int, tag: String)=WakeLock().also { latest=it }
 companion object { const val PARTIAL_WAKE_LOCK=1 }
}
''',
"Context.kt": '''package android.content
import android.app.NotificationManager
import android.os.PowerManager
import java.io.*
open class Context(val filesDir: File=File("."), val assets: Assets=Assets(byteArrayOf())) {
 val permissions=mutableSetOf<String>();val power=PowerManager()
 @Suppress("UNCHECKED_CAST") fun <T> getSystemService(c: Class<T>): T? = when(c) {
  PowerManager::class.java -> power as T
  NotificationManager::class.java -> NotificationManager() as T
  else -> null
 }
 fun getString(id: Int)=id.toString()
 fun startForegroundService(i: Intent) {}
 fun stopService(i: Intent)=true
}
class Intent(c: Context, k: Class<*>) {
 var action: String?=null;private val extras=mutableMapOf<String,String>()
 fun addFlags(f: Int)=this
 fun setAction(a: String)=apply { action=a }
 fun putExtra(k: String,v: String)=apply { extras[k]=v }
 fun getStringExtra(k: String)=extras[k]
 companion object { const val FLAG_ACTIVITY_SINGLE_TOP=1;const val FLAG_ACTIVITY_CLEAR_TOP=2 }
}
class Assets(var bytes: ByteArray) {
 var opens=0;var failOpen=0;var failAfter=0;var descriptorAvailable=true
 var transform: ((Int,ByteArray)->ByteArray)?=null
 fun open(name: String): InputStream {
  opens++;if(opens==failOpen) throw IOException("asset open failure")
  val data=transform?.invoke(opens,bytes) ?: bytes
  if(failAfter>0 && opens==2) return object: InputStream() {
   var index=0
   override fun read(): Int {
    if(index>=failAfter) throw IOException("interrupted copy")
    return data[index++].toInt() and 255
   }
  }
  return ByteArrayInputStream(data)
 }
 fun openFd(n: String): Descriptor {
  if(!descriptorAvailable) throw IOException("compressed asset")
  return Descriptor(bytes.size.toLong())
 }
}
class Descriptor(val length: Long): AutoCloseable { override fun close() {} }
''',
"PackageManager.kt": '''package android.content.pm
object PackageManager { const val PERMISSION_GRANTED=0;const val PERMISSION_DENIED=-1 }
object ServiceInfo { const val FOREGROUND_SERVICE_TYPE_CAMERA=64;const val FOREGROUND_SERVICE_TYPE_LOCATION=8 }
''',
"Service.kt": '''package android.app
import android.Manifest
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
open class Service: Context() {
 var stopped=0;var promoted=0;var promotedTypes=0;var failPromotion=false
 open fun onBind(i: Intent?): IBinder?=null
 open fun onStartCommand(i: Intent?,f: Int,id: Int)=0
 open fun onDestroy() {}
 fun stopSelf() { stopped++ }
 fun stopForeground(f: Int) {}
 fun startForeground(id: Int,n: Notification,types: Int) {
  if(failPromotion) error("promotion rejected")
  if(Build.VERSION.SDK_INT>=34) {
   if(Manifest.permission.CAMERA !in permissions) throw SecurityException("camera denied")
   if(types and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0 &&
    Manifest.permission.ACCESS_FINE_LOCATION !in permissions &&
    Manifest.permission.ACCESS_COARSE_LOCATION !in permissions) throw SecurityException("location denied")
  }
  promoted++;promotedTypes=types
 }
 fun startForeground(id: Int,n: Notification) { promoted++ }
 companion object { const val START_NOT_STICKY=2;const val START_STICKY=1;const val STOP_FOREGROUND_REMOVE=1 }
}
class Notification
class NotificationChannel(id: String,n: String,i: Int) { fun setShowBadge(b: Boolean) {} }
class NotificationManager {
 fun getNotificationChannel(id: String): NotificationChannel?=null
 fun createNotificationChannel(c: NotificationChannel) {}
 companion object { const val IMPORTANCE_LOW=2 }
}
class PendingIntent { companion object {
 const val FLAG_IMMUTABLE=1
 fun getActivity(c: Context,id: Int,i: Intent,f: Int)=PendingIntent()
 fun getService(c: Context,id: Int,i: Intent,f: Int)=PendingIntent()
} }
''',
"Compat.kt": '''package androidx.core.app
import android.app.*
import android.content.Context
object NotificationCompat { class Builder(c: Context,ch: String) {
 fun setContentTitle(s: String)=this;fun setContentText(s: String)=this;fun setSmallIcon(i: Int)=this
 fun setOngoing(b: Boolean)=this;fun setSilent(b: Boolean)=this;fun setContentIntent(p: PendingIntent)=this
 fun addAction(i: Int,s: String,p: PendingIntent)=this;fun build()=Notification()
} }
''',
"ContextCompat.kt": '''package androidx.core.content
import android.content.Context
import android.content.pm.PackageManager
object ContextCompat { fun checkSelfPermission(c: Context,p: String)=
 if(p in c.permissions) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED }
''',
"Log.kt": '''package android.util
object Log {
 fun w(t: String,m: String,e: Throwable?=null)=0;fun e(t: String,m: String,e: Throwable?=null)=0
 fun i(t: String,m: String)=0;fun d(t: String,m: String)=0
}
''',
"Graphics.kt": '''package android.graphics
class Bitmap(val width: Int,val height: Int) {
 var recycled=false;enum class Config { ARGB_8888 }
 fun recycle() { recycled=true }
 fun getPixels(p: IntArray,o: Int,s: Int,x: Int,y: Int,w: Int,h: Int) {}
 companion object {
  val created=mutableListOf<Bitmap>();var failCreate=false
  fun createBitmap(w: Int,h: Int,c: Config): Bitmap {
   if(failCreate) throw OutOfMemoryError("allocation failure")
   return Bitmap(w,h).also { created.add(it) }
  }
 }
}
class Canvas(b: Bitmap) {
 init { if(failCreate) error("canvas construction failure") }
 fun drawColor(c: Int) {};fun drawBitmap(b: Bitmap,s: Rect,d: Rect,p: Paint) {}
 companion object { var failCreate=false }
}
class Paint(f: Int) { companion object { const val FILTER_BITMAP_FLAG=1 } }
class Rect { fun set(l: Int,t: Int,r: Int,b: Int) {} }
class RectF(var left: Float=0f,var top: Float=0f,var right: Float=0f,var bottom: Float=0f) {
 fun width()=right-left;fun height()=bottom-top
 fun set(l: Float,t: Float,r: Float,b: Float) { left=l;top=t;right=r;bottom=b }
}
''',
"App.kt": '''package com.lensalpr.app
class ScanActivity
object R {
 object string { const val service_running=1;const val service_channel=2;const val service_title=3;const val service_stop=4 }
 object drawable { const val ic_launcher_foreground=1 }
}
''',
"Settings.kt": '''package com.lensalpr.app.settings
data class PlannedStep(val dwellSeconds: Int)
enum class Accelerator { NNAPI,XNNPACK }
data class DetectorModel(val asset: String="model.onnx",val inputWidth: Int=8,val inputHeight: Int=8)
data class ScanConfig(val model: DetectorModel=DetectorModel(),val accelerator: Accelerator=Accelerator.XNNPACK,val detectorThreads: Int=2)
''',
"Steps.kt": '''package com.lensalpr.app.camera
data class ZoomStep(val id: String)
''',
"Runtime.kt": '''package ai.onnxruntime
import java.io.File
import java.nio.FloatBuffer
object Probe {
 val options=mutableListOf<OrtSession.SessionOptions>();val sessions=mutableListOf<OrtSession>()
 val events=mutableListOf<String>();var failCreates=0;var failConfiguration=false;var failProvider=false
 var noInput=false;var failNextClose=false;var onRun: ()->Unit={};var rows=FloatArray(6)
 fun reset() {
  options.clear();sessions.clear();events.clear();failCreates=0;failConfiguration=false;failProvider=false
  noInput=false;failNextClose=false;onRun={};rows=FloatArray(6)
 }
}
class OrtEnvironment {
 fun createSession(path: String,o: OrtSession.SessionOptions): OrtSession {
  check(!o.closed)
  if(Probe.failCreates>0) { Probe.failCreates--;error("create failure") }
  return OrtSession(o,File(path).readBytes()).also { Probe.sessions.add(it) }
 }
 companion object { fun getEnvironment()=OrtEnvironment() }
}
class OrtSession(val options: SessionOptions,val model: ByteArray): AutoCloseable {
 var closed=false
 val inputNames: Set<String> get()=if(Probe.noInput) emptySet() else setOf("images")
 val outputNames=setOf("output")
 fun run(inputs: Map<String,OnnxTensor>): Result {
  check(!closed && !options.closed);Probe.onRun();check(!closed && !options.closed) { "closed during inference" }
  return Result(OnnxTensor(FloatBuffer.wrap(Probe.rows)))
 }
 override fun close() {
  if(Probe.failNextClose) { Probe.failNextClose=false;error("close failure") }
  check(!options.closed) { "options closed before session" };closed=true;Probe.events.add("session")
 }
 class Result(val tensor: OnnxTensor): AutoCloseable {
  operator fun get(i: Int): Any=tensor;override fun close() {}
 }
 class SessionOptions: AutoCloseable {
  var closed=false;init { Probe.options.add(this) }
  enum class OptLevel { ALL_OPT };enum class ExecutionMode { SEQUENTIAL }
  fun setOptimizationLevel(l: OptLevel) { if(Probe.failConfiguration) error("configuration failure") }
  fun setExecutionMode(m: ExecutionMode) {};fun setMemoryPatternOptimization(b: Boolean) {}
  fun setIntraOpNumThreads(i: Int) {}
  fun addNnapi() { if(Probe.failProvider) error("provider failure") }
  fun addXnnpack(m: Map<String,String>) { if(Probe.failProvider) error("provider failure") }
  override fun close() {
   check(Probe.sessions.none { it.options===this && !it.closed }) { "live session still owns options" }
   check(!closed) { "double close" };closed=true;Probe.events.add("options")
  }
 }
}
class OnnxTensor(val floatBuffer: FloatBuffer): AutoCloseable {
 override fun close() {}
 companion object { fun createTensor(e: OrtEnvironment,b: FloatBuffer,s: LongArray)=OnnxTensor(b) }
}
''',
"Json.kt": '''package org.json
// Object access doubles only. This harness does not test JSON text parsing.
class JSONObject() {
 private val values=linkedMapOf<String,Any?>()
 constructor(text: String): this() { error("JSON grammar is outside this harness") }
 fun put(k: String,v: Any?): JSONObject { values[k]=v;return this }
 fun opt(k: String): Any?=values[k]
 fun optString(k: String,f: String=""): String=values[k]?.toString() ?: f
 fun optDouble(k: String,f: Double=Double.NaN): Double=(values[k] as? Number)?.toDouble() ?: f
 fun optJSONArray(k: String): JSONArray?=values[k] as? JSONArray
 fun optJSONObject(k: String): JSONObject?=values[k] as? JSONObject
}
class JSONArray {
 private val values=mutableListOf<Any?>()
 fun put(v: Any?): JSONArray { values.add(v);return this }
 fun length()=values.size
 fun optDouble(i: Int,f: Double=Double.NaN): Double=(values.getOrNull(i) as? Number)?.toDouble() ?: f
 fun optJSONObject(i: Int): JSONObject?=values.getOrNull(i) as? JSONObject
}
''',
}

def main() -> int:
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, default=Path(__file__).resolve().parents[2])
    args=parser.parse_args()
    for tool in ('kotlinc','java'):
        if not shutil.which(tool):
            parser.error(f'{tool} must be installed and on PATH')
    source=args.repo/'app/src/main/java/com/lensalpr/app'
    paths=[source/p for p in ('ScanSessionService.kt','camera/LensRotationScheduler.kt',
          'detect/YoloDetector.kt','alpr/PlateFormats.kt','alpr/AlprResults.kt')]
    cache=source/'detect/ModelAssetCache.kt'
    if cache.exists(): paths.append(cache)
    missing=[str(p) for p in paths if not p.is_file()]
    if missing: parser.error('Missing production sources: '+', '.join(missing))
    with tempfile.TemporaryDirectory(prefix='lensalpr-host-') as name:
        temp=Path(name)
        for filename,content in STUBS.items(): (temp/filename).write_text(content,encoding='utf-8')
        jar=temp/'checks.jar'
        command=['kotlinc','-nowarn',*[str(p) for p in sorted(temp.glob('*.kt'))],
                 str(Path(__file__).with_name('Checks.kt')),*[str(p) for p in paths],'-include-runtime','-d',str(jar)]
        subprocess.run(command,check=True,timeout=180)
        return subprocess.run(['java','-jar',str(jar)],check=False,timeout=60).returncode

if __name__=='__main__':
    raise SystemExit(main())
