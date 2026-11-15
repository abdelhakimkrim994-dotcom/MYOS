package com.myos.home

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.text.Editable
import android.text.TextWatcher
import kotlin.math.*

class MainActivity : Activity(), SensorEventListener {
    private lateinit var scene: ShanshuiLauncherView
    private lateinit var search: EditText
    private lateinit var sensors: SensorManager
    private var accelerometer: Sensor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.rgb(12,13,16)
        window.navigationBarColor = Color.rgb(12,13,16)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        val root = FrameLayout(this)
        scene = ShanshuiLauncherView(this)
        root.addView(scene, FrameLayout.LayoutParams(-1,-1))
        val density = resources.displayMetrics.density
        search = EditText(this).apply {
            setSingleLine(true)
            setTextColor(Color.rgb(235,233,226))
            setHintTextColor(Color.rgb(145,145,143))
            hint = "Search applications"
            textSize = 15f
            background = null
            setPadding(0,0,0,0)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            visibility = View.GONE
            addTextChangedListener(object: TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start:Int,count:Int,after:Int) = Unit
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { scene.setQuery(s?.toString().orEmpty()) }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            setOnEditorActionListener { _, action, _ ->
                if(action==EditorInfo.IME_ACTION_SEARCH) {
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken,0)
                    clearFocus(); true
                } else false
            }
        }
        root.addView(search,FrameLayout.LayoutParams(-1,(42*density).toInt()).apply {
            leftMargin=(58*density).toInt(); rightMargin=(58*density).toInt(); topMargin=(116*density).toInt()
        })
        scene.onSearch = {
            search.visibility=View.VISIBLE; search.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(search,InputMethodManager.SHOW_IMPLICIT)
        }
        scene.onClosed = {
            search.setText(""); search.visibility=View.GONE
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken,0)
        }
        setContentView(root)
    }

    override fun onResume() { super.onResume(); if(::scene.isInitialized) scene.reloadApps(); accelerometer?.let { sensors.registerListener(this,it,SensorManager.SENSOR_DELAY_UI) } }
    override fun onPause() { if(::sensors.isInitialized) sensors.unregisterListener(this); super.onPause() }
    override fun onSensorChanged(e:SensorEvent) {
        if(e.sensor.type==Sensor.TYPE_ACCELEROMETER && ::scene.isInitialized)
            scene.setTilt((-e.values[0]/SensorManager.GRAVITY_EARTH).coerceIn(-1f,1f),((e.values[1]/SensorManager.GRAVITY_EARTH)-.5f).coerceIn(-1f,1f))
    }
    override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int)=Unit
    @Suppress("DEPRECATION")
    override fun onBackPressed() { if(::scene.isInitialized && scene.drawerOpen) scene.closeDrawer() else super.onBackPressed() }
}

private enum class Category(val title:String) {
    COMMUNICATION("Communication"), CREATE("Photography & creation"), TOOLS("Everyday tools"),
    MEDIA("Media & entertainment"), PLACES("Travel & places"), SYSTEM("System"), OTHER("Other")
}
private data class AppItem(val label:String,val pkg:String,val category:Category)

private class ShanshuiLauncherView(context:Context):View(context) {
    var drawerOpen=false
        private set
    var onSearch:(()->Unit)?=null
    var onClosed:(()->Unit)?=null
    private val d=resources.displayMetrics.density
    private val sd=resources.displayMetrics.scaledDensity
    private val wallpaper=BitmapFactory.decodeResource(resources,R.drawable.myos_shanshui_wallpaper)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val apps=mutableListOf<AppItem>()
    private val hits=mutableListOf<Pair<RectF,AppItem>>()
    private val categoryHits=mutableListOf<Pair<RectF,Category>>()
    private var query=""
    private var az=false
    private var scroll=0f
    private var maxScroll=0f
    private var downX=0f; private var downY=0f; private var lastY=0f
    private var progress=0f; private var animating=false
    private var tiltX=0f; private var tiltY=0f; private var mist=0f
    private var mistAnimator:ValueAnimator?=null
    private val bg=Color.rgb(20,21,24)
    private val night=Color.rgb(12,13,16)
    private val ink=Color.rgb(7,8,10)
    private val white=Color.rgb(231,230,224)
    private val muted=Color.rgb(150,152,151)
    private val red=Color.rgb(176,48,42)

    init { isFocusable=true; contentDescription="MYOS empty home. Swipe upward to open applications."; reloadApps() }
    fun setTilt(x:Float,y:Float) { tiltX+=(x-tiltX)*.08f; tiltY+=(y-tiltY)*.08f; invalidate() }
    fun setQuery(value:String) { query=value.trim(); scroll=0f; invalidate() }
    fun reloadApps() {
        val intent=Intent(Intent.ACTION_MAIN,null).addCategory(Intent.CATEGORY_LAUNCHER)
        val found:List<ResolveInfo> = if(Build.VERSION.SDK_INT>=33)
            context.packageManager.queryIntentActivities(intent,android.content.pm.PackageManager.ResolveInfoFlags.of(0))
        else { @Suppress("DEPRECATION") context.packageManager.queryIntentActivities(intent,0) }
        apps.clear()
        found.filter{it.activityInfo.packageName!=context.packageName}.distinctBy{it.activityInfo.packageName}.forEach {
            val label=it.loadLabel(context.packageManager).toString()
            apps.add(AppItem(label,it.activityInfo.packageName,categoryFor(label+" "+it.activityInfo.packageName)))
        }
        apps.sortBy{it.label.lowercase()}; invalidate()
    }
    private fun categoryFor(raw:String):Category {
        val s=raw.lowercase()
        return when {
            listOf("phone","dialer","contact","message","sms","mail","gmail","whatsapp","telegram").any { s.contains(it) }->Category.COMMUNICATION
            listOf("camera","gallery","photo","image","editor","paint").any { s.contains(it) }->Category.CREATE
            listOf("setting","calendar","clock","calculator","file","document","notes").any { s.contains(it) }->Category.TOOLS
            listOf("music","audio","spotify","youtube","video","player","netflix").any { s.contains(it) }->Category.MEDIA
            listOf("map","navigation","travel","location").any { s.contains(it) }->Category.PLACES
            listOf("system","security","permission","launcher").any { s.contains(it) }->Category.SYSTEM
            else->Category.OTHER
        }
    }
    fun closeDrawer() { if(!animating && drawerOpen) animate(0f,false) }
    private fun openDrawer() { if(!animating && !drawerOpen) { scroll=0f; animate(1f,true) } }
    private fun animate(target:Float,opening:Boolean) {
        animating=true; mistAnimator?.cancel()
        ValueAnimator.ofFloat(progress,target).apply {
            duration=520; interpolator=DecelerateInterpolator(1.35f)
            addUpdateListener{progress=it.animatedValue as Float;invalidate()}
            addListener(object:android.animation.AnimatorListenerAdapter(){
                override fun onAnimationEnd(animation:android.animation.Animator) {
                    progress=target; drawerOpen=opening; animating=false
                    if(opening) startMist() else { stopMist(); query=""; onClosed?.invoke() }
                    invalidate()
                }
            }); start()
        }
    }
    private fun startMist() { mistAnimator?.cancel(); mistAnimator=ValueAnimator.ofFloat(0f,1f).apply {
        duration=22000; repeatCount=ValueAnimator.INFINITE
        addUpdateListener{mist=it.animatedValue as Float;invalidate()};start()
    } }
    private fun stopMist(){mistAnimator?.cancel();mistAnimator=null}

    override fun onDraw(c:Canvas) {
        super.onDraw(c); drawHome(c)
        if(progress>0f) { c.save(); c.clipRect(0f,height*(1f-progress),width.toFloat(),height.toFloat()); c.translate(0f,(1f-progress)*height*.08f); drawDrawer(c); c.restore() }
    }
    private fun drawHome(c:Canvas) {
        val w=width.toFloat(); val h=height.toFloat(); c.drawColor(night)
        val aspect=wallpaper.width.toFloat()/wallpaper.height
        val dw=min(w*.96f,h*.96f*aspect); val dh=dw/aspect
        val x=-tiltX*2f*d; val y=-tiltY*2f*d
        paint.isFilterBitmap=true
        c.drawBitmap(wallpaper,null,RectF((w-dw)/2+x,(h-dh)/2+y,(w+dw)/2+x,(h+dh)/2+y),paint)
        // Intentionally no home icons, labels, widgets, clock, weather or visible controls.
    }
    private fun drawDrawer(c:Canvas) {
        val w=width.toFloat(); val h=height.toFloat()
        c.drawColor(bg); drawLandscape(c,w,h); drawHeader(c,w,h)
        hits.clear(); categoryHits.clear()
        val filtered=apps.filter{query.isBlank() || it.label.contains(query,true) || it.pkg.contains(query,true)}
        val groups=if(query.isNotBlank() || az) listOf("Applications" to filtered.sortedBy{it.label.lowercase()})
            else Category.values().map{it.title to filtered.filter{app->app.category==it}}.filter{it.second.isNotEmpty()}
        val left=w*.075f; val right=w*.925f; val colW=(right-left)/3f
        val icon=min(43f*d,colW*.48f)
        var y=h*.245f-scroll
        groups.forEach{(heading,items)->
            if(items.isEmpty()) return@forEach
            paint.color=white;paint.textAlign=Paint.Align.LEFT;paint.typeface=Typeface.create("sans-serif-medium",0);paint.textSize=12f*sd
            c.drawText(heading.uppercase(),left,y,paint)
            paint.color=Color.rgb(95,101,106);paint.strokeWidth=.7f*d;c.drawLine(left,y+7*d,right,y+7*d,paint)
            y+=32*d
            items.forEachIndexed{i,app->
                val col=i%3; val row=i/3
                val cx=left+colW*(col+.5f)+(if(row%2==0)0f else if(col==1)2*d else -2*d)
                val cy=y+row*104f*d
                if(cy>h*.17f-icon && cy<h+icon) {
                    drawHalo(c,cx,cy,icon*1.5f,(row+col)%2)
                    drawGlyph(c,app,cx,cy,icon)
                    paint.color=white;paint.textAlign=Paint.Align.CENTER;paint.typeface=Typeface.create("sans-serif",0);paint.textSize=10.5f*sd
                    c.drawText(if(app.label.length>15)app.label.take(14)+"…" else app.label,cx,cy+icon*.95f+15*d,paint)
                    hits.add(RectF(cx-colW*.46f,cy-icon*.8f,cx+colW*.46f,cy+icon+29*d) to app)
                }
            }
            y+=ceil(items.size/3f)*104*d+27*d
        }
        val bottom=y+scroll
        maxScroll=max(0f,bottom-h+30*d);scroll=scroll.coerceIn(0f,maxScroll)
        drawIndex(c,h)
        if(filtered.isEmpty()) {paint.color=muted;paint.textAlign=Paint.Align.CENTER;paint.textSize=13*sd;c.drawText(if(query.isBlank())"No applications found" else "No matching applications",w/2,h*.56f,paint)}
    }
    private fun drawHeader(c:Canvas,w:Float,h:Float) {
        paint.color=white;paint.textAlign=Paint.Align.CENTER;paint.typeface=Typeface.create("serif",0);paint.textSize=25*sd
        c.drawText("山水清音",w/2,h*.075f,paint)
        paint.color=muted;paint.typeface=Typeface.create("sans-serif",0);paint.textSize=9*sd
        c.drawText("M Y O S   ·   APPLICATIONS",w/2,h*.105f,paint)
        // Broken-frame vermilion maker's seal; red is reserved for the seal only.
        val x=w*.855f;val y=h*.076f;val s=17*d
        paint.color=red;paint.style=Paint.Style.STROKE;paint.strokeWidth=1.5f*d
        c.drawLine(x-s,y-s,x+s*.45f,y-s,paint);c.drawLine(x+s,y-s*.35f,x+s,y+s,paint)
        c.drawLine(x+s,y+s,x-s*.4f,y+s,paint);c.drawLine(x-s,y+s*.25f,x-s,y-s,paint)
        paint.style=Paint.Style.FILL;paint.color=red;paint.textAlign=Paint.Align.CENTER;paint.typeface=Typeface.create("serif",1);paint.textSize=10*sd
        c.drawText("MY",x,y+3*d,paint)
        paint.color=white;paint.textAlign=Paint.Align.LEFT;paint.typeface=Typeface.create("sans-serif",0);paint.textSize=10*sd
        c.drawText(if(az)"A–Z" else "GROUPED",w*.075f,h*.157f,paint)
        paint.color=muted;paint.textSize=9*sd;c.drawText("SORT",w*.075f,h*.174f,paint)
        paint.textAlign=Paint.Align.RIGHT;c.drawText(apps.size.toString()+" APPS",w*.925f,h*.165f,paint)
        paint.color=Color.rgb(190,190,185);paint.strokeWidth=1*d;c.drawLine(w*.15f,h*.205f,w*.85f,h*.205f,paint)
        paint.color=muted;paint.textAlign=Paint.Align.LEFT;paint.textSize=15*sd;c.drawText("⌕",w*.105f,h*.211f,paint)
    }
    private fun drawLandscape(c:Canvas,w:Float,h:Float) {
        ridge(c,w,h,.53f,Color.rgb(49,52,57),tiltX*5*d,tiltY*3*d)
        ridge(c,w,h,.64f,Color.rgb(36,39,44),-tiltX*8*d,tiltY*5*d)
        ridge(c,w,h,.78f,Color.rgb(27,29,33),tiltX*12*d,-tiltY*5*d)
        for(i in 0..5) {
            val x=((i*.24f+mist*(if(i%2==0).09f else -.06f))%1.3f)*w-w*.12f
            val y=h*(.31f+i*.095f);val r=w*(.17f+(i%3)*.035f)
            paint.shader=RadialGradient(x,y,r,intArrayOf(Color.argb(38,202,204,202),Color.argb(12,185,190,190),Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
            c.drawCircle(x,y,r,paint);paint.shader=null
        }
        paint.color=Color.argb(42,205,207,205);paint.style=Paint.Style.STROKE;paint.strokeWidth=.8f*d
        for(i in 0..5) {val path=Path().apply{moveTo(-w*.1f,h*(.58f+i*.055f));cubicTo(w*.2f,h*(.43f+i*.055f),w*.35f,h*(.68f+i*.04f),w*.58f,h*(.55f+i*.05f));cubicTo(w*.75f,h*(.48f+i*.05f),w*.9f,h*(.64f+i*.04f),w*1.1f,h*(.57f+i*.05f))};c.drawPath(path,paint)}
        paint.style=Paint.Style.FILL
    }
    private fun ridge(c:Canvas,w:Float,h:Float,base:Float,color:Int,ox:Float,oy:Float) {
        val path=Path().apply{moveTo(-w*.1f+ox,h*(base+.16f)+oy);cubicTo(w*.08f+ox,h*(base-.13f)+oy,w*.2f+ox,h*(base+.02f)+oy,w*.34f+ox,h*(base-.12f)+oy);cubicTo(w*.48f+ox,h*(base-.25f)+oy,w*.58f+ox,h*(base+.03f)+oy,w*.72f+ox,h*(base-.08f)+oy);cubicTo(w*.85f+ox,h*(base-.16f)+oy,w*.96f+ox,h*(base+.03f)+oy,w*1.1f+ox,h*(base+.12f)+oy);lineTo(w*1.1f,h);lineTo(-w*.1f,h);close()}
        paint.color=color;c.drawPath(path,paint)
    }
    private fun drawHalo(c:Canvas,x:Float,y:Float,r:Float,v:Int) {
        val a=if(v==0)82 else 60
        paint.shader=RadialGradient(x,y,r*1.45f,intArrayOf(Color.argb(a,211,214,211),Color.argb(28,170,176,177),Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
        c.drawCircle(x,y,r*1.45f,paint);paint.shader=null
    }
    private fun drawGlyph(c:Canvas,app:AppItem,x:Float,y:Float,size:Float) {
        c.save();c.translate(x,y);c.scale(size/48f,size/48f)
        paint.color=ink;paint.style=Paint.Style.STROKE;paint.strokeWidth=2.5f;paint.strokeCap=Paint.Cap.ROUND;paint.strokeJoin=Paint.Join.ROUND
        val n=(app.label+" "+app.pkg).lowercase()
        when {
            "camera" in n->{c.drawRoundRect(RectF(-18f,-12f,18f,14f),4f,4f,paint);c.drawCircle(0f,1f,7f,paint);c.drawLine(-7f,-12f,1f,-17f,paint)}
            listOf("photo","gallery","image").any { n.contains(it) }->{c.drawRoundRect(RectF(-18f,-15f,18f,15f),3f,3f,paint);val q=Path().apply{moveTo(-14f,10f);lineTo(-5f,0f);lineTo(1f,7f);lineTo(8f,-2f);lineTo(15f,10f)};c.drawPath(q,paint);c.drawCircle(9f,-8f,2f,paint)}
            listOf("phone","dialer").any { n.contains(it) }->{val q=Path().apply{moveTo(-12f,-17f);cubicTo(-18f,-11f,-8f,8f,9f,16f);cubicTo(15f,17f,18f,9f,13f,5f);lineTo(7f,1f);lineTo(1f,7f);cubicTo(-4f,4f,-7f,0f,-7f,-4f);lineTo(-2f,-9f);close()};c.drawPath(q,paint)}
            listOf("message","sms","chat","whatsapp","telegram").any { n.contains(it) }->{c.drawRoundRect(RectF(-18f,-14f,18f,11f),7f,7f,paint);val q=Path().apply{moveTo(-8f,11f);lineTo(-14f,18f);lineTo(1f,11f)};c.drawPath(q,paint);for(i in -8..8 step 8)c.drawCircle(i.toFloat(),-1f,1f,paint)}
            listOf("map","navigation").any { n.contains(it) }->{val q=Path().apply{moveTo(-16f,-12f);lineTo(-4f,-16f);lineTo(7f,-11f);lineTo(16f,-15f);lineTo(16f,12f);lineTo(7f,16f);lineTo(-4f,11f);lineTo(-16f,15f);close()};c.drawPath(q,paint);c.drawCircle(1f,-1f,5f,paint)}
            listOf("music","audio","spotify").any { n.contains(it) }->{val q=Path().apply{moveTo(5f,-17f);lineTo(5f,9f);cubicTo(5f,18f,-12f,19f,-12f,10f);cubicTo(-12f,4f,-3f,1f,5f,4f);moveTo(5f,-17f);lineTo(16f,-14f);lineTo(16f,-8f);lineTo(5f,-11f)};c.drawPath(q,paint)}
            listOf("mail","gmail","email").any { n.contains(it) }->{c.drawRoundRect(RectF(-18f,-13f,18f,13f),3f,3f,paint);val q=Path().apply{moveTo(-17f,-11f);lineTo(0f,2f);lineTo(17f,-11f)};c.drawPath(q,paint)}
            "calendar" in n->{c.drawRoundRect(RectF(-15f,-15f,15f,16f),3f,3f,paint);c.drawLine(-15f,-6f,15f,-6f,paint);c.drawLine(-8f,-19f,-8f,-11f,paint);c.drawLine(8f,-19f,8f,-11f,paint)}
            "setting" in n->{c.drawCircle(0f,0f,7f,paint);for(i in 0..7){val a=i*PI/4;c.drawLine(cos(a).toFloat()*11,sin(a).toFloat()*11,cos(a).toFloat()*17,sin(a).toFloat()*17,paint)}}
            else->{c.drawCircle(0f,-7f,7f,paint);c.drawArc(RectF(-14f,0f,14f,20f),180f,180f,false,paint)}
        }
        paint.style=Paint.Style.FILL;paint.color=Color.argb(100,7,8,10);c.drawCircle(-15f,16f,1f,paint);c.drawCircle(15f,-16f,.8f,paint)
        c.restore();paint.style=Paint.Style.FILL
    }
    private fun drawIndex(c:Canvas,h:Float) {
        if(query.isNotBlank()||az)return
        val cats=Category.values().filter{cat->apps.any{it.category==cat}}
        val step=min(25f*d,h*.43f/max(1,cats.size));val x=width*.955f
        cats.forEachIndexed{i,cat->paint.color=muted;paint.textAlign=Paint.Align.CENTER;paint.textSize=8*sd;c.drawText(cat.title.first().toString(),x,h*.36f+i*step,paint);categoryHits.add(RectF(x-13*d,h*.36f+i*step-12*d,x+13*d,h*.36f+i*step+12*d) to cat)}
    }
    private fun jumpTo(cat:Category) { val prior=apps.filter{it.category.ordinal<cat.ordinal}.size;scroll=(prior/3f*104*d).coerceIn(0f,maxScroll);invalidate() }

    override fun onTouchEvent(e:MotionEvent):Boolean {
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN->{downX=e.x;downY=e.y;lastY=e.y;return true}
            MotionEvent.ACTION_MOVE->{if(drawerOpen&&!animating&&downY>height*.22f){scroll=(scroll-(e.y-lastY)).coerceIn(0f,maxScroll);invalidate()};lastY=e.y;return true}
            MotionEvent.ACTION_UP->{
                val dx=e.x-downX;val dy=e.y-downY
                if(animating)return true
                if(abs(dy)>58f&&abs(dy)>abs(dx)*1.15f){if(!drawerOpen&&dy<0)openDrawer() else if(drawerOpen&&dy>0&&(scroll<=0f||downY<height*.22f))closeDrawer();return true}
                if(abs(dx)<18f&&abs(dy)<18f&&drawerOpen){
                    if(RectF(width*.05f,height*.135f,width*.28f,height*.185f).contains(e.x,e.y)){az=!az;scroll=0f;invalidate()}
                    else if(RectF(width*.08f,height*.185f,width*.92f,height*.23f).contains(e.x,e.y))onSearch?.invoke()
                    else {
                        val app=hits.firstOrNull{it.first.contains(e.x,e.y+scroll)}?.second
                        if(app!=null){context.packageManager.getLaunchIntentForPackage(app.pkg)?.let{context.startActivity(it)}}
                        else categoryHits.firstOrNull{it.first.contains(e.x,e.y)}?.second?.let(::jumpTo)
                    }
                }
                performClick();return true
            }
        };return true
    }
    override fun performClick():Boolean{super.performClick();return true}
}
