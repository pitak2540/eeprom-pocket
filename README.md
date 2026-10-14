# EEPROM Pocket

แอพ Android สำหรับอ่าน / เขียน / ลบ / ตรวจสอบ EEPROM และ SPI Flash แล้วบันทึกเป็นไฟล์ `.bin` ผ่านสาย USB OTG

## รองรับชิป
| ตระกูล | รุ่น |
|---|---|
| 24xx I2C | 24C01 – 24C512, 24C1024/24M01, 24M02 |
| 25xx/95xx SPI EEPROM | 25010 – 25512, 25M01, 25M02 |
| 25xx SPI Flash (BIOS) | 25x05 – 25x256 (W25Q, MX25L, GD25, EN25 ฯลฯ) + ตรวจ JEDEC ID อัตโนมัติ |
| 93xx Microwire | 93C46, 93C56, 93C66, 93C76, 93C86 (x8 / x16, สลับไบต์ได้) |

## รองรับอุปกรณ์
| อุปกรณ์ | VID:PID | วิธีทำงาน |
|---|---|---|
| CH341A Programmer | 1A86:5512 | ไดรเวอร์ในแอพโดยตรง (I2C/SPI ในตัว, 93xx แบบ bit-bang) |
| FTDI FT232H / FT2232H / FT4232H | 0403:6014/6010/6011 | โหมด MPSSE |
| CH340 / CH341 (UART) | 1A86:7523/5523 | เป็นสะพานไปยัง Arduino ที่แฟลช `firmware/eeprom_bridge` |
| FTDI FT232R / FT-X, Arduino CDC | 0403:6001/6015, CDC-ACM | เป็นสะพานไปยัง Arduino |

> CH340 เป็นชิป USB-Serial ล้วน ไม่มีขา I2C/SPI จึงต้องใช้บอร์ด Arduino (เช่น Nano ที่ใช้ CH340) เป็นตัวกลาง

การต่อสายทั้งหมดดูได้ในแท็บ **"วิธีต่อสาย"** ในแอพ

## Build
GitHub Actions จะ build `EepromPocket.apk` ให้อัตโนมัติทุกครั้งที่ push (ดูที่ Actions → Artifacts)
หรือ build เองด้วย Android Studio / `gradle assembleRelease`

## Windows
โฟลเดอร์ `windows/` เป็นโปรแกรมเวอร์ชัน Windows (C# WinForms) ใช้ไดรเวอร์ทางการ CH341PAR (`CH341DLL.DLL`), FTDI D2XX และพอร์ต COM สำหรับ Arduino Bridge
GitHub Actions จะสร้างตัวติดตั้ง `EEPROMPocket-Setup-x.y.z.exe` ไว้ที่ branch `win`
