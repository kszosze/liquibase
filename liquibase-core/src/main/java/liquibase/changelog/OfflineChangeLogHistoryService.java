package liquibase.changelog;

import liquibase.ContextExpression;
import liquibase.Labels;
import liquibase.change.CheckSum;
import liquibase.changelog.ChangeSet.ExecType;
import liquibase.database.Database;
import liquibase.database.OfflineConnection;
import liquibase.exception.DatabaseException;
import liquibase.exception.LiquibaseException;
import liquibase.exception.UnexpectedLiquibaseException;
import liquibase.executor.ExecutorService;
import liquibase.servicelocator.LiquibaseService;
import liquibase.statement.core.CreateDatabaseChangeLogTableStatement;
import liquibase.statement.core.MarkChangeSetRanStatement;
import liquibase.statement.core.RemoveChangeSetRanStatusStatement;
import liquibase.statement.core.UpdateChangeSetChecksumStatement;
import liquibase.util.ISODateFormat;
import liquibase.util.LiquibaseUtil;
import liquibase.util.StringUtils;
import liquibase.util.csv.CSVReader;
import liquibase.util.csv.CSVWriter;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

@LiquibaseService(skip = true)
public class OfflineChangeLogHistoryService extends AbstractChangeLogHistoryService {

    private final File changeLogFile;
    private boolean executeDmlAgainstDatabase = true;
    /**
     * Output CREATE TABLE LIQUIBASECHANGELOG or not
     */
    private boolean executeDdlAgainstDatabase = true;
    private int COLUMN_ID = 0;
    private int COLUMN_AUTHOR = 1;
    private int COLUMN_FILENAME = 2;
    private int COLUMN_DATEEXECUTED = 3;
    private int COLUMN_ORDEREXECUTED = 4;
    private int COLUMN_EXECTYPE = 5;
    private int COLUMN_MD5SUM = 6;
    private int COLUMN_DESCRIPTION = 7;
    private int COLUMN_COMMENTS = 8;
    private int COLUMN_TAG = 9;
    private int COLUMN_LIQUIBASE = 10;
    private int COLUMN_CONTEXTS = 11;
    private int COLUMN_LABELS = 12;
    private Integer lastChangeSetSequenceValue;

    public OfflineChangeLogHistoryService(Database database, File changeLogFile, boolean executeDmlAgainstDatabase, boolean executeDdlAgainstDatabase) {
        setDatabase(database);
        this.executeDmlAgainstDatabase = executeDmlAgainstDatabase;
        this.executeDdlAgainstDatabase = executeDdlAgainstDatabase;

        changeLogFile = changeLogFile.getAbsoluteFile();
        this.changeLogFile = changeLogFile;
    }

    @Override
    public int getPriority() {
        return 500;
    }

    @Override
    public boolean supports(Database database) {
        return database.getConnection() != null && database.getConnection() instanceof OfflineConnection;
    }

    public boolean isExecuteDmlAgainstDatabase() {
        return executeDmlAgainstDatabase;
    }

    public void setExecuteDmlAgainstDatabase(boolean executeDmlAgainstDatabase) {
        this.executeDmlAgainstDatabase = executeDmlAgainstDatabase;
    }

    public boolean isExecuteDdlAgainstDatabase() {
        return executeDdlAgainstDatabase;
    }

    public void setExecuteDdlAgainstDatabase(boolean executeDdlAgainstDatabase) {
        this.executeDdlAgainstDatabase = executeDdlAgainstDatabase;
    }


    @Override
    public void reset() {

    }

    @Override
    public void init() throws DatabaseException {
        if (!changeLogFile.exists()) {
            changeLogFile.getParentFile().mkdirs();
            try {
                changeLogFile.createNewFile();
                writeHeader(changeLogFile);

                if (isExecuteDdlAgainstDatabase()) {
                    ExecutorService.getInstance().getExecutor(getDatabase()).execute(new CreateDatabaseChangeLogTableStatement());
                }


            } catch (Exception e) {
                throw new UnexpectedLiquibaseException(e);
            }
        }

    }

    protected void writeHeader(File file) throws IOException {
        FileWriter writer = null;
        try {
            writer = new FileWriter(file);
            CSVWriter csvWriter = new CSVWriter(writer);
            csvWriter.writeNext(new String[]{
                    "ID",
                    "AUTHOR",
                    "FILENAME",
                    "DATEEXECUTED",
                    "ORDEREXECUTED",
                    "EXECTYPE",
                    "MD5SUM",
                    "DESCRIPTION",
                    "COMMENTS",
                    "TAG",
                    "LIQUIBASE",
                    "CONTEXTS",
                    "LABELS"                    
            });
        } finally {
            if (writer != null) {
                writer.flush();
                writer.close();
            }
        }
    }

    @Override
    protected void replaceChecksum(final ChangeSet changeSet) throws DatabaseException {
        if (isExecuteDmlAgainstDatabase()) {
            ExecutorService.getInstance().getExecutor(getDatabase()).execute(new UpdateChangeSetChecksumStatement(changeSet));
        }
        replaceChangeSet(changeSet, new ReplaceChangeSetLogic() {
            @Override
            public String[] execute(String[] line) {
                line[COLUMN_MD5SUM] = changeSet.generateCheckSum().toString();
                return line;
            }
            });
    }

    @Override
    public List<RanChangeSet> getRanChangeSets() throws DatabaseException {
        FileReader reader = null;
        try {
            reader = new FileReader(this.changeLogFile);
            CSVReader csvReader = new CSVReader(reader);
            String[] line = csvReader.readNext();

            if (line == null) { //empty file
                writeHeader(this.changeLogFile);
                return new ArrayList<RanChangeSet>();
            }
            if (!line[COLUMN_ID].equals("ID")) {
                throw new DatabaseException("Missing header in file "+this.changeLogFile.getAbsolutePath());
            }

            List<RanChangeSet> returnList = new ArrayList<RanChangeSet>();
            while ((line = csvReader.readNext()) != null) {
                ContextExpression contexts = new ContextExpression();
                if (line.length > COLUMN_CONTEXTS) {
                    contexts = new ContextExpression(line[COLUMN_CONTEXTS]);
                }
                Labels labels = new Labels();
                if (line.length > COLUMN_LABELS) {
                    labels = new Labels(line[COLUMN_LABELS]);
                }

                returnList.add(new RanChangeSet(
                        line[COLUMN_FILENAME],
                        line[COLUMN_ID],
                        line[COLUMN_AUTHOR],
                        CheckSum.parse(line[COLUMN_MD5SUM]),
                        new ISODateFormat().parse(line[COLUMN_DATEEXECUTED]),
                        line[COLUMN_TAG],
                        ChangeSet.ExecType.valueOf(line[COLUMN_EXECTYPE]),
                        line[COLUMN_DESCRIPTION],
                        line[COLUMN_COMMENTS],
                        contexts,
                        labels));
            }

            return returnList;
        } catch (Exception e) {
            throw new DatabaseException(e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignore) { }
            }
        }
    }

    protected void replaceChangeSet(ChangeSet changeSet, ReplaceChangeSetLogic replaceLogic) throws DatabaseException {
        File oldFile = this.changeLogFile;
        File newFile = new File(oldFile.getParentFile(), oldFile.getName()+".new");

        FileReader reader = null;
        FileWriter writer = null;

        try {
            reader = new FileReader(oldFile);
            writer = new FileWriter(newFile);
            CSVReader csvReader = new CSVReader(reader);
            CSVWriter csvWriter = new CSVWriter(writer);
            String[] line;
            while ((line = csvReader.readNext()) != null) {
                if (changeSet == null || (line[COLUMN_ID].equals(changeSet.getId()) && line[COLUMN_AUTHOR].equals(changeSet.getAuthor()) && line[COLUMN_FILENAME].equals(changeSet.getFilePath()))) {
                    line = replaceLogic.execute(line);
                }
                if (line != null) {
                    csvWriter.writeNext(line);
                }
            }

            csvWriter.flush();
            csvWriter.close();
            writer = null;

            csvReader.close();
            reader = null;


            oldFile.delete();
            newFile.renameTo(oldFile);
        } catch (Exception e) {
            throw new DatabaseException(e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignore) { }
            }
            if (writer != null) {
                try {
                    writer.flush();
                    writer.close();
                } catch (IOException ignore) {}
            }
        }
    }

    protected void appendChangeSet(ChangeSet changeSet, ChangeSet.ExecType execType) throws DatabaseException {
        File oldFile = this.changeLogFile;
        File newFile = new File(oldFile.getParentFile(), oldFile.getName()+".new");

        FileReader reader = null;
        FileWriter writer = null;

        try {
            reader = new FileReader(oldFile);
            writer = new FileWriter(newFile);
            CSVReader csvReader = new CSVReader(reader);
            CSVWriter csvWriter = new CSVWriter(writer);
            String[] line;
            while ((line = csvReader.readNext()) != null) {
                csvWriter.writeNext(line);
            }

            String[] newLine = new String[13];
            newLine[COLUMN_ID] = changeSet.getId();
            newLine[COLUMN_AUTHOR] = changeSet.getAuthor();
            newLine[COLUMN_FILENAME] =  changeSet.getFilePath();
            newLine[COLUMN_DATEEXECUTED] = new ISODateFormat().format(new java.sql.Timestamp(new Date().getTime()));
            newLine[COLUMN_ORDEREXECUTED] = String.valueOf(getNextSequenceValue());
            newLine[COLUMN_EXECTYPE] = execType.value;
            newLine[COLUMN_MD5SUM] = changeSet.generateCheckSum().toString();
            newLine[COLUMN_DESCRIPTION] = changeSet.getDescription();
            newLine[COLUMN_COMMENTS] = changeSet.getComments();
            newLine[COLUMN_TAG] = "";
            newLine[COLUMN_LIQUIBASE] = LiquibaseUtil.getBuildVersion().replaceAll("SNAPSHOT", "SNP");
            newLine[COLUMN_CONTEXTS] = changeSet.getContexts() == null ? null : changeSet.getContexts().toString();
            newLine[COLUMN_LABELS] = changeSet.getLabels() == null ? null : changeSet.getLabels().toString();

            csvWriter.writeNext(newLine);

            csvWriter.flush();
            csvWriter.close();
            writer = null;

            csvReader.close();
            reader = null;

            oldFile.delete();
            newFile.renameTo(oldFile);
        } catch (Exception e) {
            throw new DatabaseException(e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignore) { }
            }
            if (writer != null) {
                try {
                    writer.flush();
                    writer.close();
                } catch (IOException ignore) {}
            }
        }
    }

    @Override
    public void setExecType(final ChangeSet changeSet, final ChangeSet.ExecType execType) throws DatabaseException {
        if (isExecuteDmlAgainstDatabase()) {
            ExecutorService.getInstance().getExecutor(getDatabase()).execute(new MarkChangeSetRanStatement(changeSet, execType));
            getDatabase().commit();
        }

        if (execType.equals(ChangeSet.ExecType.FAILED) || execType.equals(ChangeSet.ExecType.SKIPPED)) {
            return; //do nothing
        } else  if (execType.ranBefore) {
            replaceChangeSet(changeSet, new ReplaceChangeSetLogic() {
                @Override
                public String[] execute(String[] line) {
                    line[COLUMN_DATEEXECUTED] = new ISODateFormat().format(new java.sql.Timestamp(new Date().getTime()));
                    line[COLUMN_MD5SUM] = changeSet.generateCheckSum().toString();
                    line[COLUMN_EXECTYPE] = execType.value;
                    return line;
                }
            });
        } else {
            appendChangeSet(changeSet, execType);
        }
    }

    @Override
    public void removeFromHistory(ChangeSet changeSet) throws DatabaseException {
        if (isExecuteDmlAgainstDatabase()) {
            ExecutorService.getInstance().getExecutor(getDatabase()).execute(new RemoveChangeSetRanStatusStatement(changeSet));
            getDatabase().commit();
        }

        replaceChangeSet(changeSet, new ReplaceChangeSetLogic() {
            @Override
            public String[] execute(String[] line) {
                return null;
            }
        });
    }

    @Override
    public int getNextSequenceValue() throws LiquibaseException {
        if (lastChangeSetSequenceValue == null) {
            lastChangeSetSequenceValue = 0;

            FileReader reader = null;
            try {
                reader = new FileReader(this.changeLogFile);
                CSVReader csvReader = new CSVReader(reader);
                String[] line = csvReader.readNext(); //skip header line

                List<RanChangeSet> returnList = new ArrayList<RanChangeSet>();
                while ((line = csvReader.readNext()) != null) {
                    try {
                        lastChangeSetSequenceValue = Integer.valueOf(line[COLUMN_ORDEREXECUTED]);
                    } catch (NumberFormatException ignore) { }
                }
            } catch (Exception ignore) {
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (IOException ignore) { }
                }
            }

        }

        return ++lastChangeSetSequenceValue;
    }

    @Override
    public void tag(final String tagString) throws DatabaseException {
        RanChangeSet last = null;
        List<RanChangeSet> ranChangeSets = getRanChangeSets();
        if (ranChangeSets.isEmpty()) {
            ChangeSet emptyChangeSet = new ChangeSet(String.valueOf(new Date().getTime()), "liquibase", false, false, "liquibase-internal", null, null, getDatabase().getObjectQuotingStrategy(), null);
            appendChangeSet(emptyChangeSet, ExecType.EXECUTED);
            last = new RanChangeSet(emptyChangeSet);
        } else {
            last = ranChangeSets.get(ranChangeSets.size() - 1);
        }

        ChangeSet lastChangeSet = new ChangeSet(last.getId(), last.getAuthor(), false, false, last.getChangeLog(), null, null, true, null, null);
        replaceChangeSet(lastChangeSet, new ReplaceChangeSetLogic() {
            @Override
            public String[] execute(String[] line) {
                line[COLUMN_TAG] = tagString;
                return line;
            }
        });
    }

    @Override
    public boolean tagExists(String tag) throws DatabaseException {
        List<RanChangeSet> ranChangeSets = getRanChangeSets();
        for (RanChangeSet changeset : ranChangeSets) {
            if (tag.equals(changeset.getTag())) {
                return true;
            }
        }
        return false;
    }

    private interface ReplaceChangeSetLogic {
        public String[] execute(String[] line);
    }

    @Override
    public void clearAllCheckSums() throws LiquibaseException {
        replaceChangeSet(null, new ReplaceChangeSetLogic() {
            @Override
            public String[] execute(String[] line) {
                line[COLUMN_MD5SUM] = null;
                return line;
            }
        });

    }

    @Override
    public void destroy() throws DatabaseException {
        if (changeLogFile.exists() && !changeLogFile.delete()) {
            throw new DatabaseException("Could not delete changelog history file "+changeLogFile.getAbsolutePath());
        }
    }
}
