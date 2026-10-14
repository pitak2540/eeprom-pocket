namespace EepromPocket;

internal static class HelpText
{
    public const string Text = @"ไดรเวอร์ที่ต้องติดตั้ง (ครั้งเดียว)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
• CH341A Programmer → ไดรเวอร์ CH341PAR ของ WCH
    https://www.wch-ic.com/downloads/CH341PAR_EXE.html
    ติดตั้งแล้วใน Device Manager ต้องขึ้น ""Interface → USB-EPP/I2C... CH341A""
    (ถ้าเคยติดตั้งโปรแกรม CH341A Programmer หรือ AsProgrammer มาแล้ว ใช้ได้เลย)
    ⚠ ถ้าเคยเปลี่ยนไดรเวอร์เป็น WinUSB ด้วย Zadig ให้ถอนแล้วลง CH341PAR ใหม่
• FTDI FT232H / FT2232H / FT4232H → ไดรเวอร์ FTDI D2XX/CDM (Windows Update มักลงให้อัตโนมัติ)
    https://ftdichip.com/drivers/d2xx-drivers/
• CH340 / Arduino → ไดรเวอร์ CH341SER (จะได้พอร์ต COM)
    https://www.wch-ic.com/downloads/CH341SER_EXE.html

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
1) CH341A Programmer
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
• ตั้งจัมเปอร์ให้อยู่โหมด Programmer (ไม่ใช่ UART) และตั้งจัมเปอร์แรงดัน 3.3V / 5V ให้ตรงกับชิป
• 24xx: ช่อง 24 · 25xx: ช่อง 25
• 93xx: ใช้ช่อง 25 ผ่านอะแดปเตอร์
    93 ขา1 CS  ← 25 ขา1 (CS)
    93 ขา2 SK  ← 25 ขา6 (CLK)
    93 ขา3 DI  ← 25 ขา5 (MOSI)
    93 ขา4 DO  → 25 ขา2 (MISO)
    93 ขา5 GND → 25 ขา4
    93 ขา8 VCC → 25 ขา8
    93 ขา6 ORG → VCC = x16, GND = x8 (เลือกให้ตรงในโปรแกรม)
⚠ บอร์ดดำรุ่นเก่า จัมเปอร์เปลี่ยนแค่ไฟ VCC แต่ขาสัญญาณยังเป็น 5V — วัดขา CS เทียบ GND ตอนตั้ง 3.3V ถ้าได้ 5V ควรใช้อะแดปเตอร์

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
2) FTDI MPSSE (ช่อง A)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  AD0 = SCK / SCL / SK      AD1 = MOSI / DI ของชิป 93
  AD2 = MISO / DO ของชิป 93  AD3 = CS
• I2C: ต่อ AD1 กับ AD2 รวมเป็น SDA, AD0 = SCL, R pull-up 4.7k ไป VCC

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
3) CH340 / Arduino Bridge
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
แฟลชไฟล์ firmware\eeprom_bridge\eeprom_bridge.ino (อยู่ในโฟลเดอร์ที่ติดตั้งโปรแกรม) ลงบอร์ด Arduino Nano/UNO ด้วย Arduino IDE แล้วเลือกพอร์ต COM ในโปรแกรม
• I2C 24xx: A4 = SDA, A5 = SCL (+ pull-up 4.7k)
• SPI 25xx: D10 = CS, D11 = MOSI, D12 = MISO, D13 = SCK
• 93xx: D10 = CS, D13 = SK, D11 = DI, D12 = DO
⚠ Arduino 5V — ชิป 3.3V ต้องใช้ level shifter

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
ขาชิป
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
24xx: 1-3 A0-A2 → GND, 4 GND, 5 SDA, 6 SCL, 7 WP → GND, 8 VCC
25xx: 1 CS, 2 DO, 3 WP → VCC, 4 GND, 5 DI, 6 CLK, 7 HOLD → VCC, 8 VCC
93xx: 1 CS, 2 SK, 3 DI, 4 DO, 5 GND, 6 ORG, 7 NC, 8 VCC

ขั้นตอน
1. เลือกชนิดอุปกรณ์ → เชื่อมต่อ
2. เลือกชิป (Flash เลือก Auto แล้วกด ""ตรวจ ID"")
3. อ่าน → บันทึกไฟล์ (สำรองก่อนเสมอ) — อ่าน 2 รอบเทียบ CRC32 ให้ตรงกัน
4. เปิดไฟล์ → เขียน (ติ๊ก Verify)

93xx
• x16: ไฟล์เรียงไบต์ต่ำก่อน (เหมือน IMSProg / CH341A Programmer) — ติ๊ก ""ไบต์สูงก่อน"" ถ้าไฟล์มาจากเครื่องที่เรียงกลับกัน
• การเขียนจะอ่านทีละเวิร์ด ค่าตรงแล้วข้าม ไม่ตรงจะเขียนแล้วอ่านกลับเช็ค (ลองใหม่สูงสุด 3 ครั้ง) ไม่ต้องลบชิปก่อน
";
}
