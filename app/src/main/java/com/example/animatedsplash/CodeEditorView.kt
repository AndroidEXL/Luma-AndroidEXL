package com.example.animatedsplash

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatEditText
import java.util.LinkedHashSet
import java.util.regex.Pattern

data class CodeCompletion(
    val label: String,
    val detail: String,
    val kind: String
)

class CodeEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatEditText(context, attrs) {

    var onVariablesChanged: ((Set<String>) -> Unit)? = null
    var onSuggestionsChanged: ((List<CodeCompletion>) -> Unit)? = null
    var onSelectionChanged: (() -> Unit)? = null

    private var applyingHighlight = false
    private var variables = linkedSetOf<String>()
    private var functions = linkedSetOf<String>()
    private var libraries = linkedSetOf<String>()

    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            if (applyingHighlight) return
            highlightSyntax()
            val code = s?.toString().orEmpty()
            val newVariables = collectVariables(code)
            val newFunctions = collectFunctions(code)
            val newLibraries = collectLibraries(code)
            if (newVariables != variables) {
                variables = newVariables
                onVariablesChanged?.invoke(variables)
            }
            functions = newFunctions
            libraries = newLibraries
            onSuggestionsChanged?.invoke(completionCandidates(currentWord()))
        }
    }

    init {
        typeface = android.graphics.Typeface.MONOSPACE
        setTextIsSelectable(true)
        addTextChangedListener(watcher)
    }

    fun applyCode(code: String) {
        applyingHighlight = true
        setText(code)
        setSelection(text?.length ?: 0)
        applyingHighlight = false
        highlightSyntax()
        variables = collectVariables(code)
        functions = collectFunctions(code)
        libraries = collectLibraries(code)
        onVariablesChanged?.invoke(variables)
        onSuggestionsChanged?.invoke(completionCandidates(currentWord()))
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (!applyingHighlight) onSelectionChanged?.invoke()
    }

    fun currentWord(): String {
        val value = text?.toString().orEmpty()
        val cursor = selectionStart.coerceAtLeast(0).coerceAtMost(value.length)
        var start = cursor
        while (start > 0 && (value[start - 1].isLetterOrDigit() || value[start - 1] == '_')) start--
        return value.substring(start, cursor)
    }

    fun completionCandidates(prefix: String): List<CodeCompletion> {
        val normalized = prefix.trim()
        if (normalized.length < 2) return emptyList()

        val candidates = linkedMapOf<String, CodeCompletion>()
        fun add(label: String, detail: String, kind: String) {
            if (label.startsWith(normalized, ignoreCase = true)) {
                candidates[label] = CodeCompletion(label, detail, kind)
            }
        }

        listOf(
            "void" to "keyword", "setup" to "Arduino entry point", "loop" to "Arduino entry point",
            "int" to "type", "float" to "type", "double" to "type", "long" to "type",
            "boolean" to "type", "bool" to "type", "char" to "type", "byte" to "type",
            "String" to "Arduino String", "array" to "collection type", "const" to "keyword",
            "pinMode" to "void pinMode(pin, mode)", "digitalWrite" to "void digitalWrite(pin, value)",
            "digitalRead" to "int digitalRead(pin)", "analogWrite" to "void analogWrite(pin, value)",
            "analogRead" to "int analogRead(pin)", "analogReference" to "set analog reference",
            "analogReadResolution" to "set analog resolution", "analogWriteResolution" to "set PWM resolution",
            "delay" to "void delay(milliseconds)", "delayMicroseconds" to "void delayMicroseconds(us)",
            "millis" to "unsigned long millis()", "micros" to "unsigned long micros()",
            "pulseIn" to "unsigned long pulseIn(pin, value)", "pulseInLong" to "long pulseInLong(pin, value)",
            "tone" to "tone(pin, frequency)", "noTone" to "stop tone on pin",
            "map" to "long map(value, from, to)", "constrain" to "constrain(value, min, max)",
            "min" to "minimum of two values", "max" to "maximum of two values", "abs" to "absolute value",
            "pow" to "power calculation", "sqrt" to "square root", "sin" to "sine", "cos" to "cosine",
            "tan" to "tangent", "random" to "random number", "randomSeed" to "seed random generator",
            "Serial" to "HardwareSerial object", "Serial1" to "HardwareSerial port 1",
            "Serial2" to "HardwareSerial port 2", "Serial3" to "HardwareSerial port 3",
            "begin" to "Serial.begin(baud)", "end" to "Serial.end()", "available" to "bytes available",
            "read" to "read next byte", "peek" to "peek next byte", "flush" to "wait for transmission",
            "print" to "print value", "println" to "print value and line break", "write" to "write bytes",
            "find" to "find text in stream", "parseInt" to "parse integer", "parseFloat" to "parse float",
            "attachInterrupt" to "attach interrupt handler", "detachInterrupt" to "detach interrupt",
            "digitalPinToInterrupt" to "convert pin to interrupt", "interrupts" to "enable interrupts",
            "noInterrupts" to "disable interrupts", "yield" to "release CPU time",
            "F" to "store string in flash", "PROGMEM" to "store data in program memory",
            "bitRead" to "read bit", "bitWrite" to "write bit", "bitSet" to "set bit", "bitClear" to "clear bit",
            "bit" to "create bit mask", "highByte" to "get high byte", "lowByte" to "get low byte",
            "word" to "combine two bytes", "isAlpha" to "check alphabetic", "isDigit" to "check digit",
            "isSpace" to "check whitespace", "isHexadecimalDigit" to "check hexadecimal digit"
        ).forEach { (label, detail) -> add(label, detail, "SNIP") }

        functions.forEach { add(it, "project function()", "FN") }
        variables.forEach { add(it, "project variable", "VAR") }
        libraries.forEach { add(it, "imported library", "LIB") }
        return candidates.values.take(50)
    }

    private fun collectVariables(code: String): LinkedHashSet<String> {
        val found = linkedSetOf<String>()
        val declaration = Pattern.compile(
            "\\b(?:const\\s+)?(?:unsigned\\s+)?(?:int|long|float|double|boolean|bool|char|String)\\s+([A-Za-z_][A-Za-z0-9_]*)"
        )
        val matcher = declaration.matcher(code)
        while (matcher.find()) matcher.group(1)?.let(found::add)
        return found
    }

    private fun collectFunctions(code: String): LinkedHashSet<String> {
        val found = linkedSetOf<String>()
        val declaration = Pattern.compile(
            "\\b(?:void|int|long|float|double|boolean|bool|char|String)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\("
        )
        val matcher = declaration.matcher(code)
        while (matcher.find()) matcher.group(1)?.let(found::add)
        return found
    }

    private fun collectLibraries(code: String): LinkedHashSet<String> {
        val found = linkedSetOf<String>()
        val matcher = Pattern.compile("#include\\s*[<\"]([^>\"]+)[>\"]").matcher(code)
        while (matcher.find()) {
            matcher.group(1)?.substringBeforeLast(".")?.let(found::add)
        }
        return found
    }

    private fun highlightSyntax() {
        val editable = text ?: return
        val source = editable.toString()
        val selection = selectionStart
        applyingHighlight = true
        editable.getSpans(0, editable.length, ForegroundColorSpan::class.java).forEach {
            editable.removeSpan(it)
        }
        editable.getSpans(0, editable.length, StyleSpan::class.java).forEach {
            editable.removeSpan(it)
        }

        applyPattern(editable, source, "//[^\\n]*|/\\*[\\s\\S]*?\\*/", Color.rgb(94, 154, 105), true)
        applyPattern(editable, source, "\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'", Color.rgb(205, 145, 76), false)
        applyPattern(editable, source, "\\b(?:void|int|float|long|double|boolean|bool|char|String|const|unsigned|if|else|for|while|return|true|false)\\b", Color.rgb(161, 125, 232), true)
        applyPattern(editable, source, "\\b(?:setup|loop|pinMode|digitalWrite|digitalRead|analogWrite|analogRead|delay|delayMicroseconds|millis|micros|map|constrain)\\b", Color.rgb(46, 171, 198), false)
        applyPattern(editable, source, "\\b(?:Serial|HIGH|LOW|INPUT|OUTPUT|INPUT_PULLUP)\\b", Color.rgb(219, 103, 151), true)
        applyPattern(editable, source, "#include|\\b(?:LiquidCrystal|Servo|DHT|Wire|SPI|EEPROM)\\b", Color.rgb(226, 113, 184), true)
        applyPattern(editable, source, "\\b\\d+(?:\\.\\d+)?\\b", Color.rgb(205, 154, 73), false)
        if (selection >= 0 && selection <= editable.length) setSelection(selection)
        applyingHighlight = false
    }

    private fun applyPattern(
        editable: Spannable,
        source: String,
        regex: String,
        color: Int,
        bold: Boolean
    ) {
        val matcher = Pattern.compile(regex).matcher(source)
        while (matcher.find()) {
            editable.setSpan(
                ForegroundColorSpan(color),
                matcher.start(),
                matcher.end(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            if (bold) {
                editable.setSpan(
                    StyleSpan(android.graphics.Typeface.BOLD),
                    matcher.start(),
                    matcher.end(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }
}
