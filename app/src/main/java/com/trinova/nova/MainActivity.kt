package com.trinova.nova

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*

class MainActivity : Activity() {
    private lateinit var tts: TextToSpeech
    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private val prefs by lazy { getSharedPreferences("nova", MODE_PRIVATE) }
    private val h = Handler(Looper.getMainLooper())
    private var pending: (() -> Unit)? = null
    private var askHome: String? = null
    private fun px(i: Int) = (i * resources.displayMetrics.density).toInt()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        tts = TextToSpeech(this) {}
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#BFE6FF")); setPadding(px(12), px(36), px(12), px(12)) }
        root.addView(TextView(this).apply { text = "🐰"; textSize = 56f; gravity = Gravity.CENTER })
        root.addView(TextView(this).apply { text = "Nova"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(Color.parseColor("#4A2411")) })
        val setup = LinearLayout(this).apply { gravity = Gravity.CENTER }
        setup.addView(Button(this).apply { text = "Accessibility"; setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } })
        setup.addView(Button(this).apply { text = "Notifications"; setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } })
        root.addView(setup)
        scroll = ScrollView(this); chat = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(chat); root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        val row = LinearLayout(this)
        input = EditText(this).apply { hint = "Talk to Nova…"; setSingleLine() }
        row.addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(Button(this).apply { text = "🎤"; setOnClickListener { listen() } })
        row.addView(Button(this).apply { text = "Send"; setOnClickListener { submit() } })
        root.addView(row); setContentView(root)
        bubble("Hi, I'm Nova. First tap Accessibility and Notifications above and switch Nova on. Then try: \"book a ride to Nehru Park\", \"check my messages\", \"open YouTube\", \"help\".", false)
    }

    private fun submit() { val t = input.text.toString().trim(); if (t.isEmpty()) return; input.setText(""); bubble(t, true); handle(t) }
    private fun listen() = try {
        startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM), 1)
    } catch (e: Exception) { say("Voice input isn't available on this phone. Type instead.") }
    @Deprecated("") override fun onActivityResult(r: Int, c: Int, d: Intent?) {
        if (r == 1 && c == RESULT_OK) d?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { bubble(it, true); handle(it) }
    }

    private fun bubble(t: String, user: Boolean) {
        val v = TextView(this).apply {
            text = t; textSize = 16f; setTextColor(if (user) Color.WHITE else Color.parseColor("#4A2411")); setPadding(px(12), px(8), px(12), px(8))
            background = GradientDrawable().apply { cornerRadius = px(16).toFloat(); setColor(Color.parseColor(if (user) "#4A2411" else "#FFFFFF")) }
        }
        val lp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { setMargins(0, px(4), 0, px(4)); gravity = if (user) Gravity.END else Gravity.START }
        chat.addView(v, lp); scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
    private fun say(t: String) { bubble(t, false); tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "n") }

    private fun go(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val c: Context = NovaAccessibilityService.inst ?: this
        try { c.startActivity(i) } catch (e: Exception) { say("I couldn't do that: ${e.message}") }
    }
    private fun installed(p: String) = try { packageManager.getPackageInfo(p, 0); true } catch (e: Exception) { false }
    private fun back() = go(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    private fun findApp(name: String): Intent? {
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val hit = packageManager.queryIntentActivities(q, 0).firstOrNull { it.loadLabel(packageManager).toString().lowercase().contains(name) }
        return hit?.let { packageManager.getLaunchIntentForPackage(it.activityInfo.packageName) }
    }

    private fun handle(t: String) {
        val l = t.lowercase()
        askHome?.let { val d = it; askHome = null; prefs.edit().putString("home", t).apply(); say("Saved \"$t\" as Home, privately on this phone."); ride(d); return }
        pending?.let {
            if (Regex("^(yes|yeah|yep|ok|okay|sure|book it|send it|do it|confirm)\\b").containsMatchIn(l)) { pending = null; it(); return }
            if (Regex("^(no|nope|cancel|stop)\\b").containsMatchIn(l)) { pending = null; say("Okay, cancelled."); return }
        }
        Regex("^(?:my )?(home|work|school)(?: location| address)? is\\s+(.+)", RegexOption.IGNORE_CASE).find(t)?.let {
            prefs.edit().putString(it.groupValues[1].lowercase(), it.groupValues[2]).apply(); say("Saved ${it.groupValues[1]}: ${it.groupValues[2]}"); return }
        Regex("^forget (?:my )?(\\w+)").find(l)?.let { if (prefs.contains(it.groupValues[1])) { prefs.edit().remove(it.groupValues[1]).apply(); say("Forgot your ${it.groupValues[1]}."); return } }
        Regex("^remember (?:that )?(.+)").find(t)?.let { prefs.edit().putString("note_" + System.currentTimeMillis(), it.groupValues[1]).apply(); say("Remembered."); return }
        if (Regex("what do you remember|my memory").containsMatchIn(l)) { say(prefs.all.entries.joinToString("\n") { it.key.removePrefix("note_") + " → " + it.value }.ifEmpty { "My memory is empty." }); return }
        Regex("(?:book|get|order|call).*(?:ride|cab|taxi).* to (.+)").find(t)?.let { ride(it.groupValues[1]); return }
        Regex("^(?:open|launch|start) (.+)").find(l)?.let { m ->
            val i = findApp(m.groupValues[1].trim()); if (i == null) say("I can't find an app called ${m.groupValues[1]}.") else { say("Opening ${m.groupValues[1]}."); go(i) }; return }
        Regex("alarm.*?(\\d{1,2})(?::(\\d\\d))?\\s*(am|pm)?").find(l)?.let {
            var hr = it.groupValues[1].toInt(); val mi = it.groupValues[2].ifEmpty { "0" }.toInt()
            if (it.groupValues[3] == "pm" && hr < 12) hr += 12; if (it.groupValues[3] == "am" && hr == 12) hr = 0
            go(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, hr).putExtra(AlarmClock.EXTRA_MINUTES, mi).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            say("Alarm set for %d:%02d.".format(hr, mi)); return }
        Regex("timer.*?(\\d+)\\s*(sec|min)").find(l)?.let {
            val s = it.groupValues[1].toInt() * (if (it.groupValues[2] == "min") 60 else 1)
            go(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, s).putExtra(AlarmClock.EXTRA_SKIP_UI, true)); say("Timer started."); return }
        Regex("^call (\\+?[\\d ]{5,})").find(l)?.let { go(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + it.groupValues[1].replace(" ", "")))); say("Dialling."); return }
        Regex("^(?:whatsapp|message|text) (\\+?\\d[\\d ]{6,}?) (.+)").find(t)?.let {
            val num = it.groupValues[1].replace(" ", "").removePrefix("+"); val msg = it.groupValues[2]
            say("Send \"$msg\" to $num on WhatsApp?")
            pending = { go(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$num?text=" + Uri.encode(msg)))); h.postDelayed({ say(if (NovaAccessibilityService.inst?.clickText("Send") == true) "Sent." else "Opened the chat, but tap Send yourself.") }, 4000) }
            return }
        if (Regex("(check|read|any|new).*(message|whatsapp|notification)|anything important").containsMatchIn(l)) { checkMessages(); return }
        Regex("^reply (.+)").find(t)?.let { m -> val x = Inbox.items.firstOrNull { it.reply != null }
            say(if (x != null && Inbox.reply(this, x, m.groupValues[1])) "Sent to ${x.title}." else "No message to reply to."); return }
        Regex("(?:navigate|directions|take me) to (.+)").find(l)?.let { go(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(it.groupValues[1])))); say("Starting navigation."); return }
        if (l.contains("movie")) { go(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode("best movies coming this month")))); say("Here are this month's movies."); return }
        if (Regex("restaurant|food near").containsMatchIn(l)) { go(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode("restaurants")))); say("Showing restaurants nearby."); return }
        Regex("^(?:search|google|look up) (.+)").find(l)?.let { go(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(it.groupValues[1])))); say("Searching."); return }
        if (l.contains("wifi") || l.contains("wi-fi")) { go(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)); say("Here are the Wi-Fi controls. Android doesn't let apps toggle Wi-Fi directly."); return }
        if (l.startsWith("help")) { say("I can: open apps, set alarms and timers, call, WhatsApp a number, read and reply to notifications, navigate, search, remember things, and compare ride prices in Uber/Rapido/Ola."); return }
        if (Regex("^(hi|hello|hey)\\b").containsMatchIn(l)) { say("Hi! What can I do for you?"); return }
        say("I don't know that yet. Say \"help\" to see what I can do.")
    }

    private fun checkMessages() {
        if (!Inbox.connected) { say("I need notification access. Tap Notifications at the top and switch Nova on."); return }
        val list = Inbox.items.toList()
        if (list.isEmpty()) { say("No new messages."); return }
        say("You have ${list.size}. " + list.take(5).joinToString(". ") { "${it.title}: ${it.text}" })
        val last = list.firstOrNull { it.reply != null } ?: return
        val sug = if (Regex("arriv|come|coming|when|\\d").containsMatchIn(last.text.lowercase())) "Okay, see you then" else "Okay 👍"
        bubble("Reply \"$sug\" to ${last.title}?", false)
        pending = { say(if (Inbox.reply(this, last, sug)) "Sent." else "I couldn't send that reply.") }
    }

    private fun openRide(pkg: String, home: String, dest: String) {
        if (pkg == "com.ubercab") go(Intent(Intent.ACTION_VIEW, Uri.parse("uber://?action=setPickup&pickup[formatted_address]=${Uri.encode(home)}&dropoff[formatted_address]=${Uri.encode(dest)}")))
        else packageManager.getLaunchIntentForPackage(pkg)?.let { go(it) }
    }

    private fun ride(dest: String) {
        val home = prefs.getString("home", null)
        if (home == null) { askHome = dest; say("Where is your home?"); return }
        val apps = listOf("Uber" to "com.ubercab", "Rapido" to "com.rapido.passenger", "Ola" to "com.olacabs.customer").filter { installed(it.second) }
        if (apps.isEmpty()) { say("I can't find Uber, Rapido or Ola on this phone."); return }
        if (NovaAccessibilityService.inst == null) { say("I need Accessibility access to read prices. Tap Accessibility at the top and switch Nova on."); return }
        say("Checking ${apps.joinToString { it.first }} from $home to $dest. This takes about ${apps.size * 10} seconds.")
        val prices = mutableMapOf<String, Int>()
        fun step(i: Int) {
            if (i >= apps.size) {
                back()
                if (prices.isEmpty()) { say("I couldn't read any prices. You may need to log in, or the app layout changed."); return }
                val b = prices.minByOrNull { it.value }!!
                say("Cheapest is ${b.key} at ₹${b.value}. Book it?")
                pending = { openRide(apps.first { it.first == b.key }.second, home, dest)
                    h.postDelayed({ val ok = NovaAccessibilityService.inst?.clickText("Confirm", "Book", "Request", "Choose") == true
                        say(if (ok) "I tapped confirm in ${b.key}. Check the app for your driver's ETA." else "I opened ${b.key} but couldn't find the confirm button. Please tap it.") }, 9000) }
                return
            }
            openRide(apps[i].second, home, dest)
            h.postDelayed({
                val s = NovaAccessibilityService.inst?.screenText() ?: ""
                Regex("₹\\s?(\\d+)").findAll(s).map { it.groupValues[1].toInt() }.minOrNull()?.let { prices[apps[i].first] = it }
                step(i + 1)
            }, 10000)
        }
        step(0)
    }
}
