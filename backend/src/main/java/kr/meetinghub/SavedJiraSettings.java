package kr.meetinghub;

import jakarta.persistence.*;
import org.springframework.data.jpa.repository.JpaRepository;

@Entity @Table(name="jira_settings")
class SavedJiraSettings {
    @Id public String id="jira";
    public String mode="mock",siteUrl="",email="",cloudId="",defaultProject="";
    @Column(columnDefinition="text") public String encryptedToken;
    @Version public long version;
}

interface JiraSettingsRepository extends JpaRepository<SavedJiraSettings,String> {}
