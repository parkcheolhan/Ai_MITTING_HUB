import {useEffect,useRef,useState,type ReactNode} from 'react';

export default function TicketDetailsDrawer({open,onClose,blocked,children,fallbackFocus}:{open:boolean;onClose:()=>void;blocked:boolean;children:ReactNode;fallbackFocus?:()=>HTMLElement|null}){
  const dialog=useRef<HTMLDialogElement>(null),opener=useRef<HTMLElement|null>(null),previousOverflow=useRef<string|null>(null);
  const closeTimer=useRef<ReturnType<typeof setTimeout>|null>(null);
  const fallback=useRef(fallbackFocus);fallback.current=fallbackFocus;
  const [present,setPresent]=useState(open),[closing,setClosing]=useState(false);
  function restore(){
    const wasOpen=previousOverflow.current!==null||opener.current!==null;
    if(previousOverflow.current!==null){document.documentElement.style.overflow=previousOverflow.current;previousOverflow.current=null;}
    const target=opener.current?.isConnected?opener.current:null;opener.current=null;
    if(wasOpen)requestAnimationFrame(()=>{if(!dialog.current?.open)(target?.isConnected?target:fallback.current?.())?.focus({preventScroll:true});});
  }
  function finish(){if(closeTimer.current!==null){clearTimeout(closeTimer.current);closeTimer.current=null;}dialog.current?.close();restore();setClosing(false);setPresent(false);}
  useEffect(()=>{
    const element=dialog.current;if(!element)return;
    if(open){
      if(!element.open){const active=document.activeElement as HTMLElement|null;opener.current=active&&active!==document.body&&active!==document.documentElement?active:null;previousOverflow.current=document.documentElement.style.overflow;document.documentElement.style.overflow='hidden';element.showModal();}
      setPresent(true);setClosing(false);
    }else if(element.open){setClosing(true);closeTimer.current=setTimeout(finish,280);return()=>{if(closeTimer.current!==null){clearTimeout(closeTimer.current);closeTimer.current=null;}};}
  },[open]);
  useEffect(()=>{const element=dialog.current;return()=>{element?.close();restore();};},[]);
  function requestClose(){if(!blocked&&!closing)onClose();}
  return <dialog ref={dialog} className={'ticket-details-drawer '+(closing?'closing':'')} aria-label="티켓 상세정보" aria-modal="true" onCancel={e=>{e.preventDefault();requestClose();}} onClick={e=>{if(e.target===e.currentTarget)requestClose();}}>
    <div className="ticket-details-sheet" onAnimationEnd={e=>{if(e.target===e.currentTarget&&closing&&!open)finish();}}>{(open||present)&&children}</div>
  </dialog>;
}
