EEPROM Pocket for Windows 1.1
==============================

อ่าน / เขียน / ลบ / ตรวจสอบ EEPROM 24xx, 25xx, 93xx และ SPI Flash 25xx แล้วบันทึกเป็นไฟล์ .bin

อุปกรณ์ที่รองรับ
- CH341A Programmer  -> ต้องติดตั้งไดรเวอร์ CH341PAR ของ WCH
  https://www.wch-ic.com/downloads/CH341PAR_EXE.html
- FTDI FT232H / FT2232H / FT4232H (MPSSE) -> ไดรเวอร์ FTDI D2XX
  https://ftdichip.com/drivers/d2xx-drivers/
- CH340 / Arduino Bridge (พอร์ต COM) -> ไดรเวอร์ CH341SER + แฟลช firmware\eeprom_bridge\eeprom_bridge.ino ลงบอร์ด Arduino

วิธีต่อสายและขั้นตอนใช้งานอยู่ในแท็บ "วิธีต่อสาย / ไดรเวอร์" ในโปรแกรม

ข้อควรระวัง
- อ่านแล้วบันทึกไฟล์สำรองก่อนเขียนทุกครั้ง
- ตั้งจัมเปอร์แรงดัน 3.3V / 5V ให้ตรงกับชิป
