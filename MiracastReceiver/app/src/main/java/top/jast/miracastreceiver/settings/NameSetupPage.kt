package top.jast.miracastreceiver.settings

import top.jast.miracastreceiver.util.Base64Util
import java.security.MessageDigest

object NameSetupPage {
    // Fixed script; preset values stay in escaped HTML attributes, never executable code.
    private const val PRESET_SCRIPT = "var input=document.getElementById('name');var tags=document.querySelectorAll('.preset');function mark(){tags.forEach(function(tag){tag.setAttribute('aria-pressed',tag.getAttribute('data-name')===input.value?'true':'false');});}tags.forEach(function(tag){tag.addEventListener('click',function(){input.value=tag.getAttribute('data-name');input.dispatchEvent(new Event('input',{bubbles:true}));});});input.addEventListener('input',mark);mark();"
    val scriptHash: String = Base64Util.encode(MessageDigest.getInstance("SHA-256")
        .digest(PRESET_SCRIPT.toByteArray(Charsets.UTF_8)))

    fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    fun render(title: String, hint: String, name: String, presets: List<String>, save: String, message: String,
               saved: Boolean = false): String =
        """<!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>${escape(title)}</title><style>
        body{font:20px system-ui,sans-serif;max-width:32rem;margin:2rem auto;padding:1rem;background:#101827;color:#fff}
        input,button{box-sizing:border-box;width:100%;font:inherit;padding:1rem;margin:.5rem 0;border-radius:.5rem}
        button{background:#81c8ff;color:#101827;border:0}p{line-height:1.6;overflow-wrap:anywhere}
        .presets{display:flex;flex-wrap:wrap;gap:.5rem;margin:1rem 0}
        .preset{width:auto;margin:0;padding:.4rem .8rem;min-height:36px;font-size:1rem;border-radius:999px;
        background:#1d2c43;color:#cae6ff;border:1px solid #58718d;cursor:pointer}
        .preset[aria-pressed=true]{background:#81c8ff;color:#101827;border-color:#81c8ff}
        .preset:focus-visible{outline:2px solid #fff;outline-offset:2px}
        .notice{position:sticky;top:0;z-index:1;padding:1.25rem;border:3px solid #ffb4ab;border-radius:.75rem;
        background:#5c2020;box-shadow:0 4px 20px #0008;overflow-wrap:anywhere}
        .success{background:#123e2b;border-color:#7ce6a4}.saved-name{font-size:1.6rem;font-weight:700;margin-top:.75rem}
        #result{scroll-margin-top:1rem}
        </style></head><body>
        ${if (message.isNotEmpty()) """<section id="result" class="notice${if (saved) " success" else ""}" role="alert">
        <strong>${if (saved) "✓" else "!"} ${escape(message)}</strong>
        ${if (saved) "<div class=\"saved-name\">${escape(name)}</div>" else ""}</section>""" else ""}
        <h1>${escape(title)}</h1><p>${escape(hint)}</p>
        <form method="post" action="#result" accept-charset="UTF-8">
        <div class="presets">
        ${presets.joinToString("") { "<button type=\"button\" class=\"preset\" data-name=\"${escape(it)}\" aria-pressed=\"false\">${escape(it)}</button>" }}
        </div>
        <label for="name">${escape(title)}</label>
        <input id="name" name="name" required value="${escape(name)}" autocomplete="off">
        <button type="submit">${escape(save)}</button></form><script>$PRESET_SCRIPT</script></body></html>""".trimIndent()
}
