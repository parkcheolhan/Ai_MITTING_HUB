package kr.meetinghub;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import kr.meetinghub.Domain.*;

@Component
public class ConfiguredJira implements Adapters.Jira {
    final JiraSettingsStore settings;final ObjectMapper json;
    public ConfiguredJira(JiraSettingsStore settings,ObjectMapper json){this.settings=settings;this.json=json;}
    @Override public Adapters.Jira snapshot(){
        var config=settings.current();return config.mode().equals("cloud")?new CloudJira(config,json):new Adapters.MockJira();
    }
    @Override public String mode(){return settings.view().get("mode").toString();}
    @Override public String target(){return snapshot().target();}
    @Override public Result create(Draft draft){return snapshot().create(draft);}
    @Override public void updateDescription(String key,String description){snapshot().updateDescription(key,description);}
    @Override public boolean attachmentExists(String key,String filename,long size){return snapshot().attachmentExists(key,filename,size);}
    @Override public void attach(String key,java.nio.file.Path file,String filename,String mediaType){snapshot().attach(key,file,filename,mediaType);}
    @Override public Result linkExisting(Draft draft){return snapshot().linkExisting(draft);}
    @Override public Optional<Result> find(Draft draft){return snapshot().find(draft);}
    @Override public Map<String,Object> test(){return snapshot().test();}
    @Override public List<Map<String,String>> projects(){return snapshot().projects();}
    @Override public List<Map<String,String>> issueTypes(String project){return snapshot().issueTypes(project);}
    @Override public List<Map<String,Object>> fields(String project,String type){return snapshot().fields(project,type);}
    @Override public JiraEpicPage epics(String project,String query,String nextPageToken){return snapshot().epics(project,query,nextPageToken);}
    @Override public JiraEpic epic(String project,String key){return snapshot().epic(project,key);}
    @Override public JiraUserPage assignableUsers(String project,String query,String accountId,int startAt){return snapshot().assignableUsers(project,query,accountId,startAt);}
}
