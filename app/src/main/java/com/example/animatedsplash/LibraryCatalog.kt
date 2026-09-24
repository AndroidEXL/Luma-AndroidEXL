package com.example.animatedsplash

data class ArduinoLibrary(
    val name: String,
    val description: String,
    val category: String,
    val version: String = "",
    val downloadUrl: String? = null,
    val archiveFileName: String? = null,
    val official: Boolean = true
)

object LibraryCatalog {
    val all: List<ArduinoLibrary> = listOf(
        ArduinoLibrary("AudioZero", "تشغيل ملفات صوتية من بطاقة SD", "Signal Input/Output"),
        ArduinoLibrary("LiquidCrystal", "واجهة شاشات LCD النصية", "Display"),
        ArduinoLibrary("Servo", "التحكم بالمحركات المؤازرة", "Motor"),
        ArduinoLibrary("Wire", "اتصال I2C بين اللوحات والحساسات", "Communication"),
        ArduinoLibrary("SPI", "اتصال SPI للأجهزة الطرفية", "Communication"),
        ArduinoLibrary("EEPROM", "حفظ البيانات في الذاكرة الدائمة", "Storage"),
        ArduinoLibrary("SoftwareSerial", "منفذ تسلسلي برمجي", "Communication"),
        ArduinoLibrary("DHT sensor library", "حساسات الحرارة والرطوبة DHT", "Sensor"),
        ArduinoLibrary("Adafruit Unified Sensor", "واجهة موحدة للحساسات", "Sensor"),
        ArduinoLibrary("NewPing", "حساسات المسافة Ultrasonic", "Sensor"),
        ArduinoLibrary("IRremote", "استقبال إشارات الأشعة تحت الحمراء", "Input"),
        ArduinoLibrary("Keypad", "لوحات المفاتيح المصفوفية", "Input"),
        ArduinoLibrary("LiquidCrystal I2C", "شاشات LCD عبر I2C", "Display"),
        ArduinoLibrary("Adafruit GFX Library", "رسم واجهات رسومية", "Display"),
        ArduinoLibrary("Adafruit SSD1306", "شاشات OLED SSD1306", "Display"),
        ArduinoLibrary("WiFi", "اتصال Wi-Fi للوحات الداعمة", "Network"),
        ArduinoLibrary("Ethernet", "اتصال Ethernet", "Network"),
        ArduinoLibrary("BluetoothSerial", "اتصال Bluetooth للوحات ESP32", "Network"),
        ArduinoLibrary("ArduinoJson", "قراءة وكتابة JSON", "Data"),
        ArduinoLibrary("FastLED", "التحكم بشرائط LED", "Lighting"),
        ArduinoLibrary("PubSubClient", "اتصال MQTT", "Network")
    )
}
