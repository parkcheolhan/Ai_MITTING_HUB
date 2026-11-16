import {useLayoutEffect,useRef,type TextareaHTMLAttributes} from 'react';

export default function AutoSizeTextarea(props:TextareaHTMLAttributes<HTMLTextAreaElement>){
  const ref=useRef<HTMLTextAreaElement>(null);
  useLayoutEffect(()=>{
    const el=ref.current;if(!el)return;
    const resize=()=>{el.style.height='auto';const border=el.offsetHeight-el.clientHeight;el.style.height=`${Math.max(180,el.scrollHeight+border)}px`;};
    resize();let width=el.getBoundingClientRect().width;
    const observer=new ResizeObserver(()=>{const next=el.getBoundingClientRect().width;if(Math.abs(next-width)>.5){width=next;resize();}});
    observer.observe(el);return()=>observer.disconnect();
  },[props.value]);
  return <textarea {...props} ref={ref} className={`auto-size-textarea ${props.className||''}`}/>;
}
