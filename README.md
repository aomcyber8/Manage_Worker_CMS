# Worker CMS

โปรแกรมภายในสำหรับตรวจสอบและปิดสิทธิ์ใช้งาน Worker ในระบบ SENA/SENX จากไฟล์ Excel พัฒนาด้วย Java 21, Spring Boot, Selenium และ Apache POI

## ความสามารถ

- เลือกระบบ SENA (`sena.on.lk`) หรือ SENX (`senx.on.lk`)
- อ่าน Excel จากหัวตาราง 7 คอลัมน์: `วันที่พ้นสภาพ`, `ID`, `No`, `ชื่อ`, `E-mail`, `ตำแหน่ง`, `แผนก`
- ตรวจหัวตาราง ข้อมูล ID/ชื่อ และชื่อซ้ำก่อนเริ่มงาน
- ค้นหาด้วยชื่อหลังตัดคำนำหน้า `นาย`, `นาง`, `นางสาว`, `น.ส.` และค้นซ้ำด้วยนามสกุลเมื่อจำเป็น
- เปิดหน้า `/Common/Employee` โดยตรงหลัง Login
- รองรับ Dry Run และการเปลี่ยน Status เป็น `Ex-Employee`
- แสดง progress และผลการค้นหาทีละรายการ
- Export ตารางพร้อมผลการค้นหาเป็น `.xlsx`
- บันทึก log และ screenshot เฉพาะกรณีผิดพลาด

## สิ่งที่ต้องติดตั้ง

- JDK 21
- Maven 3.9 ขึ้นไป
- Google Chrome

สคริปต์ `.bat` จะตรวจตำแหน่งที่ติดตั้งบนเครื่องนี้ก่อน และใช้คำสั่งจาก `PATH` เป็น fallback ตรวจสอบการติดตั้งด้วย:

```powershell
java -version
mvn -version
```

## การตั้งค่า

สร้างไฟล์ `.env` ในโฟลเดอร์โปรแกรม:

```env
CMS_USERNAME=your_username
CMS_PASSWORD=your_password
CMS_URL=https://sena.on.lk
EXCEL_FILE_PATH=./data/worker_list.xlsx
EXCEL_START_ROW=2
EXCEL_SHEET_NAME=
DELAY_BETWEEN_RECORDS=0.2
HEADLESS=false
```

`EXCEL_START_ROW` ใช้เพื่อรองรับ CLI รุ่นเดิมและหมายถึงแถวข้อมูลแรก โปรแกรมจึงอ่านหัวตารางจากแถวก่อนหน้า ส่วนหน้าเว็บให้ผู้ใช้ระบุ “แถวหัวตาราง” โดยตรง

## Build และทดสอบ

ดับเบิลคลิก `build.bat` หรือรัน `mvn package` ผลลัพธ์อยู่ที่ `target/worker-cms-1.0.0.jar`

## เปิดหน้าเว็บ

ดับเบิลคลิก `run-web.bat` แล้วเปิด [http://127.0.0.1:8765](http://127.0.0.1:8765)

เซิร์ฟเวอร์รับการเชื่อมต่อจากเครื่องนี้เท่านั้น หน้าเว็บมี CSRF token, ตรวจ Host และไม่อนุญาตให้เริ่มงานซ้อนกัน

## ใช้งานผ่าน command line

Dry Run:

```powershell
run-cli-dry.bat --file data\worker_list.xlsx --site SENA --header-row 1
```

ปิดสิทธิ์ใช้งานจริง:

```powershell
run-cli.bat --file data\worker_list.xlsx --site SENX --header-row 1
```

โปรแกรมจะถามยืนยันก่อนเปลี่ยนสถานะจริง ใช้ `--yes` เมื่อต้องการยืนยันผ่าน argument และใช้ `--sheet "Sheet1"` เพื่อระบุชื่อชีต

## ขั้นตอนทำงาน

1. อ่านและตรวจไฟล์ Excel
2. Login เข้าระบบที่เลือก
3. เปิดหน้า Employee โดยตรงและตรวจโดเมน/ช่องค้นหา
4. ค้นหา Worker ด้วยชื่อ และใช้นามสกุลเป็นคำค้นสำรอง
5. ตรวจว่าชื่อเต็มตรงเพียงหนึ่งรายการก่อนเปิดรายละเอียด
6. ใน Dry Run บันทึกว่า “พบข้อมูล” โดยไม่เปลี่ยนสถานะ หรือเปลี่ยนเป็น Ex-Employee และตรวจสถานะหลังบันทึก
7. ทำรายการถัดไปเมื่อหาไม่พบ และหยุดเมื่อเกิดข้อผิดพลาดที่ไม่สามารถยืนยันความปลอดภัยได้
8. Logout เมื่อจบหรือหยุดงาน

## โครงสร้างหลัก

```text
src/main/java/th/co/sena/workercms/  Java backend, workflow, Excel และ Selenium
src/main/resources/                 Spring Boot configuration
src/test/java/th/co/sena/workercms/ JUnit tests
web/                                HTML, CSS และ JavaScript
logs/                               log และ screenshot เมื่อทำงาน
target/worker-cms-1.0.0.jar         executable JAR หลัง build
```
