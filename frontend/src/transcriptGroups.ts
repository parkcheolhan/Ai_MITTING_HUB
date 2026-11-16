import type {Segment} from './types';
export function groupConsecutiveSegments(segments:Segment[]){
  const groups:{id:string;speaker:string;segments:Segment[]}[]=[];
  for(const segment of segments){
    const last=groups.at(-1);
    if(last&&last.speaker===segment.speaker)last.segments.push(segment);
    else groups.push({id:segment.id,speaker:segment.speaker,segments:[segment]});
  }
  return groups;
}
