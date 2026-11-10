package com.example.aiphone

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONObject

/** The panel subscribes here; every agent line shows in the overlay. */
object AgentLog {
    @Volatile
    var sink: ((String) -> Unit)? = null

    fun emit(line: String) {
        sink?.invoke(line)
    }
}

data class Step(val action: String, val target: String, val count: Int)

/** Turns a command into steps, then performs them with accessibility. */
object AgentRunner {
    @Volatile
    private var busy = false

    private val TIKTOK = listOf(
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.zhiliaoapp.musically.go"
    )

    private const val SYSTEM_PROMPT =
        "អ្នកគឺជាអ្នកបញ្ជាទូរស័ព្ទ Android។ ឆ្លើយជា JSON តែម្តង គ្មានអត្ថបទផ្សេង: " +
            "{\"steps\":[{\"action\":\"open\",\"target\":\"tiktok\"}," +
            "{\"action\":\"scroll\",\"count\":20}]} " +
            "action មានតែ open, scroll, back, home, wait ។ target ជាឈ្មោះ app ឬ package។ " +
            "count ជាចំនួនដង (1-200)។"

    fun run(context: Context, command: String, log: (String) -> Unit) {
        if (busy) {
            log("⏳ ការងារមួយកំពុងរត់ — រង់ចាំវាចប់សិន")
            return
        }
        busy = true
        val app = context.applicationContext
        Thread {
            try {
                execute(app, command, log)
            } catch (e: Exception) {
                log("✖ " + (e.message ?: "បរាជ័យ"))
            } finally {
                busy = false
            }
        }.start()
    }

    private fun execute(context: Context, command: String, log: (String) -> Unit) {
        val service = AgentAccessibilityService.instance
            ?: throw IllegalStateException("Accessibility មិនទាន់បើក — Settings › Accessibility")
        val size = service.screenSize()
        log("📐 អេក្រង់ " + size.first + "×" + size.second + " px")
        var steps = parse(command)
        if (steps.isEmpty()) {
            log("🧠 សួរ AI ដើម្បីរៀបផែនការ…")
            val reply = GroqClient.chat(context, SYSTEM_PROMPT, command, log)
            if (!reply.ok) {
                log("✖ AI: " + reply.text)
                return
            }
            log("🤖 " + reply.model + ": " + reply.text.replace("\n", " ").take(180))
            steps = parseJson(reply.text)
        }
        if (steps.isEmpty()) {
            log("✖ មិនយល់បញ្ជា — សាក: បើក tiktok អូស 20")
            return
        }
        log("▶ " + steps.size + " ជំហាន")
        for (step in steps) {
            when (step.action) {
                "open" -> {
                    val pkg = resolve(context, step.target)
                    if (pkg == null) {
                        log("✖ រកមិនឃើញ app: " + step.target)
                        return
                    }
                    if (!service.openApp(pkg)) {
                        log("✖ បើក " + pkg + " មិនបាន")
                        return
                    }
                    log("📱 បើក " + pkg)
                    Thread.sleep(2600)
                }
                "scroll" -> {
                    val total = step.count.coerceIn(1, 200)
                    for (i in 1..total) {
                        if (!service.swipeUp()) {
                            log("✖ អូសមិនបាន — សិទ្ធិ gesture ត្រូវបានបដិសេធ")
                            break
                        }
                        log("⬆ " + i + "/" + total)
                        Thread.sleep(1600)
                    }
                }
                "back" -> {
                    service.back()
                    Thread.sleep(700)
                }
                "home" -> {
                    service.home()
                    Thread.sleep(700)
                }
                "wait" -> Thread.sleep((step.count.coerceIn(1, 30) * 1000).toLong())
            }
        }
        log("✔ ចប់")
    }

    private fun parse(command: String): List<Step> {
        val steps = mutableListOf<Step>()
        val open = Regex("""(?:open\s*:|បើក)\s*([A-Za-z0-9_.]+)""", RegexOption.IGNORE_CASE).find(command)
        if (open != null) steps.add(Step("open", open.groupValues[1], 0))
        var scroll = Regex("""(?:scroll|អូស|រំកិល|swipe)\s*:?\s*(\d{1,3})""", RegexOption.IGNORE_CASE).find(command)
        if (scroll == null) {
            scroll = Regex("""(\d{1,3})\s*(?:video|វីដេអូ)""", RegexOption.IGNORE_CASE).find(command)
        }
        if (scroll != null) {
            val count = scroll.groupValues[1].toIntOrNull() ?: 20
            steps.add(Step("scroll", "", count))
        }
        return steps
    }

    private fun parseJson(text: String): List<Step> {
        val steps = mutableListOf<Step>()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return steps
        try {
            val array = JSONObject(text.substring(start, end + 1)).optJSONArray("steps")
                ?: return steps
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val action = item.optString("action").lowercase()
                if (action.isEmpty()) continue
                steps.add(Step(action, item.optString("target"), item.optInt("count", 0)))
            }
        } catch (e: Exception) {
            steps.clear()
        }
        return steps
    }

    private fun resolve(context: Context, target: String): String? {
        val query = target.trim().lowercase()
        if (query.isEmpty()) return null
        if (query.contains("tiktok") || query.contains("tik tok")) {
            for (pkg in TIKTOK) if (installed(context, pkg)) return pkg
        }
        if (installed(context, query)) return query
        val pm = context.packageManager
        var best: String? = null
        for (app in pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
            if (app.packageName == context.packageName) continue
            val label = pm.getApplicationLabel(app).toString().lowercase()
            if (label == query) return app.packageName
            if (best == null && label.contains(query)) best = app.packageName
        }
        return best
    }

    private fun installed(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0) != null
    } catch (e: Exception) {
        false
    }
}
