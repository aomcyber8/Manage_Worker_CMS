const $ = (id) => document.getElementById(id);
let state = {}, busy = false;
const today = new Date();
const localMonth = today.getFullYear() + '-' + String(today.getMonth() + 1).padStart(2, '0');
const siteLabels = {SENA:"SENA — sena.on.lk",SENX:"SENX — senx.on.lk"};
const labels = {idle:'พร้อมใช้งาน',ready:'พร้อมตรวจสอบ',running:'กำลังทำงาน',finishing:'กำลังออกจากระบบ',done:'งานเสร็จแล้ว',stopped:'หยุดงานแล้ว',failed:'งานมีข้อผิดพลาด'};
const searchResultLabels = {SUCCESS:'พบข้อมูล',SKIPPED:'พบข้อมูล',NOT_FOUND:'ไม่พบ',FAILED:'ผิดพลาด'};
function error(message) { $('error').textContent = message; $('error').hidden = !message; }
async function api(path, data) {
  const response = await fetch(path, data ? {method:'POST',headers:{'Content-Type':'application/json','X-CSRF-Token':state.token},body:JSON.stringify(data)} : {});
  const result = await response.json();
  if (!response.ok) throw new Error(result.error || 'เชื่อมต่อไม่สำเร็จ');
  return result;
}
function downloadName(response) {
  const disposition=response.headers.get('Content-Disposition') || '';
  const encoded=disposition.match(/filename\*=UTF-8''([^;]+)/i);
  if(encoded) return decodeURIComponent(encoded[1]);
  const match=disposition.match(/filename="([^"]+)"/i);
  return match ? match[1] : 'workers.xlsx';
}
async function downloadExport() {
  const response=await fetch('/api/export',{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-Token':state.token},body:'{}'});
  if(!response.ok){const result=await response.json();throw new Error(result.error || 'สร้างไฟล์ Excel ไม่สำเร็จ');}
  const blob=await response.blob(), url=URL.createObjectURL(blob), link=document.createElement('a');
  link.href=url; link.download=downloadName(response); document.body.append(link); link.click(); link.remove();
  setTimeout(()=>URL.revokeObjectURL(url),0);
}
function render() {
  const records=state.records || [], results=state.results || [], active=state.active;
  $('connection').textContent=state.configured?'● เชื่อมต่อเครื่องนี้แล้ว':'● ยังไม่ได้ตั้งค่าบัญชี CMS';
  $('phase').textContent=labels[state.phase] || state.phase;
  if(active) $('site').value=state.site;
  $('target-site').textContent=state.site ? `รายการสำหรับ ${siteLabels[state.site]}` : 'ยังไม่ได้เลือกระบบ CMS';
  $('filename').textContent=state.filename || 'ยังไม่ได้เลือกไฟล์';
  $('count').textContent=`${records.length.toLocaleString()} รายการ`;
  $('processed').textContent=`${results.length} / ${records.length}`;
  $('success').textContent=results.filter(r=>r.status==='SUCCESS').length;
  $('skipped').textContent=results.filter(r=>r.status==='SKIPPED').length;
  $('failed').textContent=results.filter(r=>['FAILED','NOT_FOUND'].includes(r.status)).length;
  $('progress').max=records.length || 1; $('progress').value=results.length;
  $('empty').hidden=records.length>0;
  $('dry').disabled=$('deactivate').disabled=busy || active || !records.length || !state.configured || !$('site').value || $('site').value!==state.site;
  $('export').disabled=busy || active || !records.length;
  for(const id of ['preview','file','header-row','sheet','site','resign-month','fetch-resign']) $(id).disabled=busy || active;
  $('stop').hidden=!active;
  const byRow=new Map(results.map(result=>[result.row,result]));
  const signature=JSON.stringify([records,results]);
  if ($('rows').dataset.signature!==signature) {
    $('rows').replaceChildren();
    for(const record of records) {
      const result=byRow.get(record.row), tr=document.createElement('tr');
      for(const text of [record.termination_date,record.id,record.no,record.name,record.email,record.position,record.department]) {
        const td=document.createElement('td'); td.textContent=text; tr.append(td);
      }
      const resultCell=document.createElement('td'), badge=document.createElement('span');
      badge.className=`status ${result?.status || ''}`;
      badge.textContent=result ? searchResultLabels[result.status] : 'รอดำเนินการ';
      resultCell.append(badge); tr.append(resultCell);
      $('rows').append(tr);
    }
    $('rows').dataset.signature=signature;
  }
  const logs=(state.logs || []).join('\n') || 'ยังไม่มีการทำงาน';
  if($('logs').textContent!==logs){$('logs').textContent=logs;$('logs').scrollTop=$('logs').scrollHeight;}
  if(state.error) error(state.error);
}
async function refresh(){state=await api('/api/state');render();}
async function action(fn){busy=true;render();error('');try{await fn();await refresh();}catch(e){error(e.message);}finally{busy=false;render();}}
$('import-progress-dialog').addEventListener('cancel',event=>event.preventDefault());
function showImportProgress(title,detail,value) {
  $('import-progress-title').textContent=title;
  $('import-progress-detail').textContent=detail;
  const progress=$('import-progress');
  if(value===null) progress.removeAttribute('value');
  else progress.value=value;
  if(!$('import-progress-dialog').open) $('import-progress-dialog').showModal();
}
$('resign-month').value=localMonth;
$('site').addEventListener('change',()=>{render();if(state.records?.length && $('site').value!==state.site) error('เปลี่ยนระบบ CMS แล้ว กรุณาอ่านไฟล์และตรวจรายการใหม่');else error('');});
$('fetch-resign').addEventListener('click',()=>action(async()=>{
  const site=$('site').value, month=$('resign-month').value;
  if(!site) throw new Error('กรุณาเลือกระบบ CMS');
  if(!month) throw new Error('กรุณาระบุเดือนและปีที่ต้องการดึงข้อมูล');
  showImportProgress('กำลังดึงข้อมูลจากเว็บ…','กำลังตรวจสอบตารางประวัติผู้ที่ลาออกแล้ว',null);
  try {
    const result=await api('/api/resign-history',{site,month});
    showImportProgress('ดึงข้อมูลสำเร็จ',result.count.toLocaleString()+' รายการ',100);
  } finally {
    if($('import-progress-dialog').open) $('import-progress-dialog').close();
  }
}));
$('upload').addEventListener('submit',event=>{event.preventDefault();action(async()=>{
  const file=$('file').files[0];
  if(!file || file.size>5*1024*1024) throw new Error('เลือกไฟล์ .xlsx ขนาดไม่เกิน 5 MB');
  showImportProgress('กำลังอ่านไฟล์ Excel…','0%',0);
  try {
    const data=await new Promise((resolve,reject)=>{
      const reader=new FileReader();
      reader.onprogress=progressEvent=>{
        if(!progressEvent.lengthComputable) return;
        const percent=Math.min(80,Math.round(progressEvent.loaded/progressEvent.total*80));
        showImportProgress('กำลังอ่านไฟล์ Excel…',`${percent}%`,percent);
      };
      reader.onload=()=>resolve(reader.result.split(',')[1]);
      reader.onerror=()=>reject(new Error('อ่านไฟล์ไม่ได้'));
      reader.readAsDataURL(file);
    });
    showImportProgress('กำลังตรวจสอบข้อมูล…','กำลังตรวจหัวตารางและข้อมูล Worker',null);
    await api('/api/preview',{site:$('site').value,filename:file.name,data,header_row:Number($('header-row').value),sheet:$('sheet').value.trim()});
    showImportProgress('อ่านไฟล์สำเร็จ','100%',100);
  } finally {
    if($('import-progress-dialog').open) $('import-progress-dialog').close();
  }
});});
$('dry').addEventListener('click',()=>action(()=>api('/api/start',{site:$('site').value,mode:'dry-run',revision:state.revision})));
$('export').addEventListener('click',()=>action(downloadExport));
$('deactivate').addEventListener('click',()=>{$('confirm-site').textContent=siteLabels[state.site];$('deactivate-count').textContent=state.records.length;$('confirmation').value='';$('confirm').showModal();$('confirmation').focus();});
$('cancel').addEventListener('click',()=>$('confirm').close());
$('confirm-form').addEventListener('submit',event=>{event.preventDefault();const confirmation=$('confirmation').value;$('confirm').close();action(()=>api('/api/start',{site:state.site,mode:'deactivate',revision:state.revision,confirmation}));});
$('stop').addEventListener('click',()=>action(async()=>{await api('/api/stop',{});$('phase').textContent='กำลังรอให้รายการปัจจุบันจบ';}));
async function poll(){try{await refresh();}catch(e){$('connection').textContent='● ขาดการเชื่อมต่อ';error('เชื่อมต่อโปรแกรมไม่ได้ กรุณาตรวจว่า Worker CMS Java ยังเปิดอยู่');}setTimeout(poll,state.active?750:1500);}poll();
