const $ = id => document.getElementById(id);
const names = {layered:'Layered image gate', periodic_matched_layered:'Periodic · same count as layered', installed_gate:'Current glasses gate', periodic_matched_installed:'Periodic · same count as current'};
const reasons = {initial_coverage:'First scene sample',new_scene_signature:'New scene appearance',new_region_signature:'New region appearance',familiar_view:'Matches a recent view',checking_new_view:'Checking whether the change persists',s0_unchanged:'Grayscale view unchanged',poor_image_quality:'Image too dark, bright, or blurred',local_model_busy:'Local model still processing',capture_budget_reached:'Capture budget reached',no_future_rgb_within_tolerance:'No RGB frame close enough after request'};
let state, position, start, end, eventStart = null, eventEnd = null, frameLoop;
const vga = $('vga'), rgb = $('rgb');
const text = (tag,value,cls) => {const node=document.createElement(tag);node.textContent=value;if(cls)node.className=cls;return node;};
const seconds = t => {const value=Math.round(Math.max(0,t-start)*10);return `${String(Math.floor(value/600)).padStart(2,'0')}:${((value%600)/10).toFixed(1).padStart(4,'0')}`;};
const decimal = value => value==null?'—':Number(value).toFixed(3);
function previousIndex(rows,value,key) {let lo=0,hi=rows.length;while(lo<hi){const mid=(lo+hi)>>1;if(rows[mid][key]<=value)lo=mid+1;else hi=mid;}return Math.max(0,lo-1);}
function mapClock(role,value,from,to) {
 const rows=state.timeline[role],i=previousIndex(rows,value,from),a=rows[i],b=rows[Math.min(i+1,rows.length-1)];
 if(a===b||b[from]===a[from])return a[to];
 const fraction=Math.max(0,Math.min(1,(value-a[from])/(b[from]-a[from])));
 return a[to]+fraction*(b[to]-a[to]);
}
function pause(){vga.pause();rgb.pause();$('play').textContent='Play';cancelAnimationFrame(frameLoop);}
function seek(time){pause();position=Math.max(start,Math.min(end,time));vga.currentTime=mapClock('vga',position,'session_seconds','pts_seconds');rgb.currentTime=mapClock('rgb',position,'session_seconds','pts_seconds');renderPosition();}
function renderPosition(){
 $('scrub').value=position;$('clock').textContent=`${seconds(position)} / ${seconds(end)}`;
 const index=previousIndex(state.decisions,position,'session_seconds'),row=state.decisions[index];
 $('decision').textContent=`${reasons[row.reason]||row.reason} · Whole ${decimal(row.global_distance)} · Region ${decimal(row.region_distance)}${row.s1_ran?` · Local model ${row.model_ms.toFixed(1)} ms`:''}`;
 const r=state.timeline.rgb[previousIndex(state.timeline.rgb,position,'session_seconds')];
 $('sync').textContent=`RGB frame offset ${Math.round((r.session_seconds-position)*1000)} ms`;
 drawTimeline();
}
function tick(){
 if(vga.paused)return;
 position=Math.max(start,Math.min(end,mapClock('vga',vga.currentTime,'pts_seconds','session_seconds')));
 if(position>=end-.02){pause();position=end;renderPosition();return;}
 const desired=mapClock('rgb',position,'session_seconds','pts_seconds');
 if(Math.abs(rgb.currentTime-desired)>.12)rgb.currentTime=desired;
 renderPosition();frameLoop=requestAnimationFrame(tick);
}
async function play(){
 if(!vga.paused){pause();return;}
 if(position>=end-.05)seek(start);
 rgb.currentTime=mapClock('rgb',position,'session_seconds','pts_seconds');
 try{await Promise.all([vga.play(),rgb.play()]);$('play').textContent='Pause';tick();}
 catch(error){$('saveStatus').textContent=`Playback could not start: ${error.message}`;pause();}
}
function drawTimeline(){
 if(!state)return;
 const canvas=$('timeline'),scale=window.devicePixelRatio||1,w=canvas.clientWidth,h=140;
 if(canvas.width!==Math.round(w*scale)||canvas.height!==h*scale){canvas.width=Math.round(w*scale);canvas.height=h*scale;}
 const c=canvas.getContext('2d');c.setTransform(scale,0,0,scale,0,0);c.clearRect(0,0,w,h);
 const x=t=>(t-start)/(end-start)*w, y=d=>120-Math.min(.7,d)/.7*104;
 for(const event of state.annotations.events){c.fillStyle=event.importance==='required'?'#7bc4db18':event.importance==='optional'?'#b8b27813':'#ffffff07';c.fillRect(x(event.start),0,x(event.end)-x(event.start),140);}
 c.strokeStyle='#34403d';c.lineWidth=1;for(const value of [0,.2,.4,.6]){c.beginPath();c.moveTo(0,y(value));c.lineTo(w,y(value));c.stroke();}
 for(const [field,color,threshold] of [['global_distance','#91e1b2',state.report.parameters.global_novelty],['region_distance','#e7b975',state.report.parameters.region_novelty]]){
  c.strokeStyle=color;c.globalAlpha=.4;c.setLineDash([4,4]);c.beginPath();c.moveTo(0,y(threshold));c.lineTo(w,y(threshold));c.stroke();c.setLineDash([]);c.globalAlpha=1;
  c.lineWidth=1.4;c.beginPath();let begun=false;for(const row of state.decisions){if(row[field]==null)continue;const px=x(row.session_seconds),py=y(row[field]);if(!begun){c.moveTo(px,py);begun=true;}else c.lineTo(px,py);}c.stroke();
 }
 c.strokeStyle='#b8eac4';c.globalAlpha=.7;for(const row of state.report.policies[$('policy').value]||[]){c.beginPath();c.moveTo(x(row.session_seconds),126);c.lineTo(x(row.session_seconds),140);c.stroke();}c.globalAlpha=1;
 c.strokeStyle='#f1f4f2';c.lineWidth=1;c.beginPath();c.moveTo(x(position),0);c.lineTo(x(position),140);c.stroke();
}
function renderCaptures(){
 const container=$('captures');container.replaceChildren();
 const captures=state.report.policies[$('policy').value];
 if(!captures.length){container.append(text('p','This policy selected no RGB frames.','muted'));drawTimeline();return;}
 for(const row of captures){
  const card=text('article','','capture');card.tabIndex=0;
  const image=document.createElement('img');image.src=`/assets/${row.rgb_thumbnail}`;image.alt=`Proposed RGB evidence at ${seconds(row.session_seconds)}`;image.loading='lazy';card.append(image);
  const caption=text('div','','caption');caption.append(text('strong',seconds(row.session_seconds)),text('span',reasons[row.reason]||row.reason.replaceAll('_',' ')));
  caption.append(text('small',row.rgb_route==='duplicate_visual_evidence'?'Local RGB: near-duplicate evidence':row.rgb_route==='review_for_vlm'?'Local RGB: new evidence for review':'Local RGB model not run'));
  caption.append(text('small',`RGB acquired ${row.pair_delay_ms.toFixed(0)} ms after request`));card.append(caption);
  card.addEventListener('click',()=>seek(row.session_seconds));card.addEventListener('keydown',event=>{if(event.key==='Enter')seek(row.session_seconds);});container.append(card);
 }drawTimeline();
}
function renderEvents(){
 $('events').replaceChildren();
 state.annotations.events.forEach((event,index)=>{
  const item=text('div','','event'),remove=text('button','Remove','subtle');remove.addEventListener('click',()=>{state.annotations.events.splice(index,1);renderEvents();drawTimeline();$('saveStatus').textContent='Labels changed; press Save labels & score.';});
  item.append(remove,text('span',event.label),text('small',`${seconds(event.start)}–${seconds(event.end)} · ${event.importance}`));item.addEventListener('dblclick',()=>seek(event.start));$('events').append(item);
 });$('reviewComplete').checked=state.annotations.review_complete;
}
function mark(which){pause();if(which==='start')eventStart=position;else eventEnd=position;$('rangeLabel').textContent=`${eventStart==null?'—':seconds(eventStart)} → ${eventEnd==null?'—':seconds(eventEnd)}`;}
function addEvent(){
 const label=$('eventLabel').value.trim();$('labelError').textContent='';
 if(eventStart==null||eventEnd==null||eventEnd<eventStart){$('labelError').textContent='Mark a start and an end in order.';return;}
 if(!label){$('labelError').textContent='Describe the new information visible here.';return;}
 state.annotations.events.push({id:crypto.randomUUID(),start:eventStart,end:eventEnd,label,importance:$('importance').value});
 state.annotations.events.sort((a,b)=>a.start-b.start);eventStart=eventEnd=null;$('eventLabel').value='';$('rangeLabel').textContent='No interval selected';renderEvents();drawTimeline();$('saveStatus').textContent='Event added; press Save labels & score.';
}
function renderMetrics(){
 const root=$('metrics');root.replaceChildren();
 if(!state.metrics){root.append(text('p','No event labels saved yet. Capture counts alone cannot measure whether the useful information was preserved.','muted'));return;}
 const table=document.createElement('table'),head=document.createElement('tr');for(const title of ['Policy','RGB captures','Useful events captured','Recall','Precision'])head.append(text('th',title));table.append(head);
 for(const [name,metrics] of Object.entries(state.metrics)){
  const row=document.createElement('tr');for(const value of [names[name]||name,state.report.policies[name].length,`${metrics.captured_required_events} / ${metrics.required_events}`,metrics.event_recall==null?'Awaiting useful events':`${Math.round(metrics.event_recall*100)}%${metrics.annotation_complete?'':' · partial labels'}`,metrics.capture_precision==null?'Awaiting full review':`${Math.round(metrics.capture_precision*100)}%`])row.append(text('td',value));table.append(row);
 }root.append(table);
}
async function save(){
 $('save').disabled=true;$('saveStatus').textContent='Saving…';
 try{state.annotations.review_complete=$('reviewComplete').checked;const response=await fetch('/api/annotations',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(state.annotations)});const body=await response.json();if(!response.ok)throw Error(body.error||'Save failed');state.annotations=body.annotations;state.metrics=body.metrics;renderMetrics();$('saveStatus').textContent='Saved locally. Event coverage updated.';}
 catch(error){$('saveStatus').textContent=error.message;}
 finally{$('save').disabled=false;}
}
async function init(){
 try{
  const response=await fetch('/api/state');if(!response.ok)throw Error('Could not load the replay.');state=await response.json();[start,end]=state.report.interval;position=start;
  $('subtitle').textContent=`${state.report.task} · ${seconds(end)} shared footage`;
  if(state.report.budget_blocked_proposals)$('notice').textContent+=` Capture budget reached: ${state.report.budget_blocked_proposals} later proposals were blocked.`;
  const reviews=state.report.policies.layered.filter(r=>r.rgb_route==='review_for_vlm').length;
  for(const [value,label] of [[seconds(end),'Shared recording'],[state.report.policies.layered.length,'RGB captures proposed'],[state.report.models.rgb?reviews:'Not run','Images needing deeper analysis'],[state.report.online_vlm_calls,'Online VLM calls']]){
   const card=text('div','','stat');card.append(text('strong',value),text('span',label));$('stats').append(card);
  }
  for(const name of Object.keys(state.report.policies)){const option=document.createElement('option');option.value=name;option.textContent=names[name]||name;$('policy').append(option);}
  for(const limitation of state.report.limitations)$('limits').append(text('li',limitation));
  $('modelInfo').textContent=`VGA: ${state.report.models.vga} · RGB: ${state.report.models.rgb||'not run'} · ${state.report.s1_inferences} local VGA checks · Model latency measured on the computer`;
  $('scrub').min=start;$('scrub').max=end;$('scrub').value=start;
  $('play').addEventListener('click',play);$('back').addEventListener('click',()=>seek(position-1));$('forward').addEventListener('click',()=>seek(position+1));$('scrub').addEventListener('input',e=>seek(Number(e.target.value)));
  $('markStart').addEventListener('click',()=>mark('start'));$('markEnd').addEventListener('click',()=>mark('end'));$('addEvent').addEventListener('click',addEvent);$('save').addEventListener('click',save);$('policy').addEventListener('change',renderCaptures);
  $('timeline').addEventListener('pointerdown',event=>{const box=$('timeline').getBoundingClientRect();seek(start+(event.clientX-box.left)/box.width*(end-start));});
  window.addEventListener('resize',drawTimeline);vga.addEventListener('ended',pause);vga.addEventListener('loadedmetadata',()=>seek(start));
  renderEvents();renderMetrics();renderCaptures();renderPosition();if(vga.readyState>=1)seek(start);
 }catch(error){$('subtitle').textContent=error.message;}
}
init();
