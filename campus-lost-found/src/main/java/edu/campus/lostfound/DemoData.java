package edu.campus.lostfound;

import java.time.LocalDate;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component @ConditionalOnProperty(name="app.seed-demo", havingValue="true")
class DemoData implements ApplicationRunner {
    private final DemoAccounts accounts; private final Reports reports;
    DemoData(DemoAccounts accounts, Reports reports) { this.accounts=accounts; this.reports=reports; }
    @Override @Transactional public void run(ApplicationArguments args) {
        if (reports.count()>0) return;
        CampusUser demo=accounts.account("finder");
        LocalDate today=LocalDate.now();
        reports.save(new ItemReport(demo.id,new Api.ReportInput("FOUND","Black backpack","BAGS","LIBRARY",today.minusDays(1),"A plain black backpack was found near the library entrance. Identifying contents are withheld.")));
        reports.save(new ItemReport(demo.id,new Api.ReportInput("LOST","Blue water bottle","OTHER","SPORTS_GROUND",today.minusDays(2),"A blue reusable bottle went missing after practice. Please submit a found report if located.")));
        reports.save(new ItemReport(demo.id,new Api.ReportInput("FOUND","Set of keys","KEYS","CAFETERIA",today,"A set of keys was found near the cafeteria seating area. Keyring details are withheld.")));
    }
}
