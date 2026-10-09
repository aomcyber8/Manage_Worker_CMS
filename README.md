# Worker CMS

โปรแกรมภายในสำหรับตรวจสอบและปิดสิทธิ์ใช้งาน Worker ในระบบ SENA/SENX จากไฟล์ Excel พัฒนาด้วย Python 3.14, Flask, Selenium และ openpyxl

## ความสามารถ

- เลือกระบบ SENA (`sena.on.lk`) หรือ SENX (`senx.on.lk`)
- ดึงข้อมูลจากตาราง `ประวัติผู้ที่ลาออกแล้ว` แบบรายเดือน หรือเลือกช่วงปีสำหรับตรวจสอบและ Export Excel
- อ่าน Excel จากหัวตาราง 7 คอลัมน์: `วันที่พ้นสภาพ`, `ID`, `No`, `ชื่อ`, `E-mail`, `ตำแหน่ง`, `แผนก`
- ค้นหาด้วยชื่อหลังตัดคำนำหน้า และค้นซ้ำด้วยนามสกุลเมื่อจำเป็น
- รองรับ Dry Run และการเปลี่ยน Status เป็น `Ex-Employee`
- Export ตาราง 8 คอลัมน์พร้อมผลการค้นหาเป็น `.xlsx`
- ข้อมูลช่วงปีรวมไว้ใน worksheet Workers เดียว เรียงวันที่ล่าสุดไปเก่าสุด และไม่ใช้ทำงานกับ CMS
- บันทึก log และ screenshot เฉพาะกรณีผิดพลาด

## สิ่งที่ต้องติดตั้ง

- Python 3.14
- Google Chrome

ดับเบิลคลิก `build.bat` ครั้งแรกเพื่อสร้าง `.venv`, ติดตั้ง dependencies จาก `requirements.txt` และรัน tests

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
RESIGN_HISTORY_URL=http://192.168.0.161/resign/index2.php
RESIGN_HISTORY_TIMEOUT_SECONDS=15
WAIT_TIMEOUT=10
SEARCH_SETTLE_MILLIS=750
STATUS_SETTLE_MILLIS=1200
ACCESS_RETRY_COUNT=3
HEADLESS=false
```

`EXCEL_START_ROW` ใช้กับ CLI และหมายถึงแถวข้อมูลแรก หน้าเว็บให้ระบุแถวหัวตารางโดยตรง

## เปิดหน้าเว็บ

ดับเบิลคลิก `run-web.bat` แล้วเปิด [http://127.0.0.1:8765](http://127.0.0.1:8765)

เซิร์ฟเวอร์ใช้ Waitress และรับการเชื่อมต่อจากเครื่องนี้เท่านั้น หน้าเว็บมี CSRF token, ตรวจ Host และป้องกันงานซ้อน

## ใช้งานผ่าน command line

Dry Run:

```powershell
run-cli-dry.bat --file data\worker_list.xlsx --site SENA --header-row 1
```

ปิดสิทธิ์ใช้งานจริง:

```powershell
run-cli.bat --file data\worker_list.xlsx --site SENX --header-row 1
```

โปรแกรมถามยืนยันก่อนเปลี่ยนสถานะจริง ใช้ `--yes` สำหรับการยืนยันผ่าน argument และ `--sheet "Sheet1"` เพื่อระบุชีต

## หลักการค้นหา Worker

1. ใช้ข้อมูลคอลัมน์ `ชื่อ` เป็นคำค้น ห้ามใช้ ID เป็นคำค้น
2. ตัดคำนำหน้า `นาย`, `นาง`, `นางสาว`, `น.ส.` รวมช่องว่างซ้ำ แล้วค้นหาใน Employee Search
3. หาก Employee Grid คืนเพียง 1 แถว ให้ถือว่าพบข้อมูลทันที; หากคืนหลายแถว ต้องพบชื่อเต็มหลังปรับตรงเพียงหนึ่งแถว และหากไม่พบจึงค้นซ้ำด้วยนามสกุล
4. ID ใช้แสดงผล, log และตรวจสอบย้อนหลังเท่านั้น
5. Dry Run เมื่อพบข้อมูลจะไม่เปิดรายละเอียดและไม่ตรวจ Status
6. หน้าเว็บอนุญาตให้ปิดสิทธิ์เฉพาะรายการที่ Dry Run แสดง `พบข้อมูล`; รายการ `ไม่พบ` จะไม่ถูกประมวลผลซ้ำ
7. หน้า Employee Detail ใช้ `input.switch-input` ที่ผูกกับ Employee/Ex-Employee เป็นสถานะจริง: checked คือ Employee และ unchecked คือ Ex-Employee
8. เมื่อเปลี่ยนสถานะแล้วจึงกด `Save Document`, รอ `Save Data Successful`, กด OK และกลับ Employee List
9. หาก CMS แสดง Access Id 89 โปรแกรมโหลดหน้า Employee ซ้ำตาม `ACCESS_RETRY_COUNT` ก่อนหยุดงาน

## ทดสอบ

```powershell
.venv\Scripts\python.exe -m pytest
```

## โครงสร้างหลัก

```text
worker_cms/  Flask backend, workflow, Excel, Selenium และ CLI
tests/       pytest tests
web/         HTML, CSS และ JavaScript
logs/        log และ screenshot เมื่อทำงาน
```