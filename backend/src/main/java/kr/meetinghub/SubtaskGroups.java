package kr.meetinghub;

import java.util.*;
import java.util.function.Function;
import kr.meetinghub.Domain.*;

final class SubtaskGroups {
    static int creationLimit(int tasks,int checks){return Math.max(1,(int)Math.ceil((tasks+checks)/6.0));}
    static List<Integer> limits(List<Integer> sizes){
        if(sizes.isEmpty())return List.of();
        int target=Math.max(sizes.size(),(int)Math.ceil(sizes.stream().mapToInt(Integer::intValue).sum()/3.0));
        var limits=new ArrayList<Integer>(Collections.nCopies(sizes.size(),1));
        for(int assigned=sizes.size();assigned<target;assigned++){
            int best=-1;double score=-Double.MAX_VALUE;
            for(int i=0;i<sizes.size();i++)if(limits.get(i)<sizes.get(i)){
                double next=sizes.get(i)/3.0-limits.get(i);if(next>score){score=next;best=i;}
            }
            if(best<0)break;limits.set(best,limits.get(best)+1);
        }
        return limits;
    }
    static List<List<TicketProposal>> groups(List<TicketProposal> rows,int limit){
        if(rows.size()<=limit)return rows.stream().map(List::of).toList();
        var groups=new ArrayList<List<TicketProposal>>();var remaining=new ArrayList<>(rows);
        groups.add(new ArrayList<>(List.of(remaining.remove(0))));
        // Seed distinct topics, then attach related work to the closest group.
        while(groups.size()<limit){
            TicketProposal seed=remaining.stream().min(Comparator.comparingDouble(p->groups.stream().mapToDouble(g->similarity(p,g.get(0))).max().orElse(0))).orElseThrow();
            groups.add(new ArrayList<>(List.of(seed)));remaining.remove(seed);
        }
        int capacity=(int)Math.ceil((double)rows.size()/limit);
        for(var row:remaining){
            var best=groups.stream().filter(g->g.size()<capacity).max(Comparator.comparingDouble(g->g.stream().mapToDouble(p->similarity(p,row)).max().orElse(0)-g.size()*0.02)).orElseThrow();best.add(row);
        }
        return groups;
    }
    static double similarity(TicketProposal a,TicketProposal b){
        var left=tokens(a);var right=tokens(b);var union=new HashSet<>(left);union.addAll(right);left.retainAll(right);return union.isEmpty()?0:(double)left.size()/union.size();
    }
    static Set<String> tokens(TicketProposal p){return new HashSet<>(Arrays.asList((p.summary()+" "+String.join(" ",p.tasks())).replaceAll("\\[[^]]+]","").split("[^\\p{L}\\p{N}]+")));}
    static List<String> collect(List<TicketProposal> group,Function<TicketProposal,List<String>> field){return group.stream().flatMap(p->field.apply(p).stream()).filter(s->!s.isBlank()).distinct().toList();}
    static TicketProposal combine(List<TicketProposal> group){
        if(group.size()==1)return group.get(0);
        var first=group.get(0);boolean checks=group.stream().allMatch(p->p.summary().startsWith("[확인]"));
        String core=SubtaskTitles.compact(first.summary()).replaceFirst("^(?:\\[[^]]+]\\s*)+","");
        String summary=SubtaskTitles.compact((checks?"[확인] ":"[작업] ")+core.replaceFirst("(?:\\s*·\\s*관련 항목 통합)+$",""));
        return new TicketProposal(collect(group,TicketProposal::analysisItemIds),summary,"",collect(group,TicketProposal::tasks),collect(group,TicketProposal::checks),collect(group,TicketProposal::acceptanceCriteria),collect(group,TicketProposal::dependencies),collect(group,TicketProposal::reviewNotes),List.of(),group.stream().anyMatch(TicketProposal::aiRecommended));
    }
    static List<TicketProposal> reduce(List<TicketProposal> rows,int limit){return groups(rows,Math.max(1,limit)).stream().map(SubtaskGroups::combine).toList();}
}
