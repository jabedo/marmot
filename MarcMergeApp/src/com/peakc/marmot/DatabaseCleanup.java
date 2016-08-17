package com.peakc.marmot;

import org.apache.log4j.Logger;
import org.ini4j.Ini;
import org.ini4j.Profile.Section;

import java.io.File;
import java.io.FilenameFilter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DatabaseCleanup implements IProcessHandler {

	@Override
	public void doCronProcess(String servername, Ini configIni, Section processSettings, Connection vufindConn, Connection econtentConn, CronLogEntry cronEntry, Logger logger) {
		CronProcessLogEntry processLog = new CronProcessLogEntry(cronEntry.getLogEntryId(), "Database Cleanup");
		processLog.saveToDatabase(vufindConn, logger);

		//Remove expired sessions
		try{
			//Make sure to normalize the time based to be milliseconds, not microseconds
			long now = new Date().getTime() / 1000;
			long defaultTimeout = Long.parseLong(Util.cleanIniValue(configIni.get("Session", "lifetime")));
			long earliestDefaultSessionToKeep = now - defaultTimeout;
			long numStandardSessionsDeleted = vufindConn.prepareStatement("DELETE FROM session where last_used < " + earliestDefaultSessionToKeep + " and remember_me = 0").executeUpdate();
			processLog.addNote("Deleted " + numStandardSessionsDeleted + " expired Standard Sessions");
			processLog.saveToDatabase(vufindConn, logger);
			long rememberMeTimeout = Long.parseLong(Util.cleanIniValue(configIni.get("Session", "rememberMeLifetime")));
			long earliestRememberMeSessionToKeep = now - rememberMeTimeout;
			long numRememberMeSessionsDeleted = vufindConn.prepareStatement("DELETE FROM session where last_used < " + earliestRememberMeSessionToKeep + " and remember_me = 1").executeUpdate();
			processLog.addNote("Deleted " + numRememberMeSessionsDeleted + " expired Remember Me Sessions");
			processLog.saveToDatabase(vufindConn, logger);
		}catch (SQLException e) {
			processLog.incErrors();
			processLog.addNote("Unable to delete expired sessions. " + e.toString());
			logger.error("Error deleting expired sessions", e);
			processLog.saveToDatabase(vufindConn, logger);
		}

		//Remove old searches 
		try {
			int rowsRemoved = 0;
			ResultSet numSearchesRS = vufindConn.prepareStatement("SELECT count(id) from search where created < (CURDATE() - INTERVAL 2 DAY) and saved = 0").executeQuery();
			numSearchesRS.next();
			long numSearches = numSearchesRS.getLong(1);
			long batchSize = 100000;
			long numBatches = (numSearches / batchSize) + 1;
			processLog.addNote("Found " + numSearches + " expired searches that need to be removed.  Will process in " + numBatches + " batches");
			processLog.saveToDatabase(vufindConn, logger);
			for (int i = 0; i < numBatches; i++){
				PreparedStatement searchesToRemove = vufindConn.prepareStatement("SELECT id from search where created < (CURDATE() - INTERVAL 2 DAY) and saved = 0 LIMIT 0, " + batchSize, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
				PreparedStatement removeSearchStmt = vufindConn.prepareStatement("DELETE from search where id = ?");
				
				ResultSet searchesToRemoveRs = searchesToRemove.executeQuery();
				while (searchesToRemoveRs.next()){
					long curId = searchesToRemoveRs.getLong("id");
					removeSearchStmt.setLong(1, curId);
					rowsRemoved += removeSearchStmt.executeUpdate();
				}
				processLog.incUpdated();
				processLog.saveToDatabase(vufindConn, logger);
			}
			processLog.addNote("Removed " + rowsRemoved + " expired searches");
			processLog.saveToDatabase(vufindConn, logger);
		} catch (SQLException e) {
			processLog.incErrors();
			processLog.addNote("Unable to delete expired searches. " + e.toString());
			logger.error("Error deleting expired searches", e);
			processLog.saveToDatabase(vufindConn, logger);
		}

		//Remove spammy searches
		try {
			PreparedStatement removeSearchStmt = vufindConn.prepareStatement("DELETE from search_stats_new where phrase like '%http:%' or phrase like '%https:%' or phrase like '%mailto:%'");

			int rowsRemoved = removeSearchStmt.executeUpdate();

			processLog.addNote("Removed " + rowsRemoved + " spammy searches");
			processLog.incUpdated();

			processLog.saveToDatabase(vufindConn, logger);

			PreparedStatement removeSearchStmt2 = vufindConn.prepareStatement("DELETE from analytics_search where lookfor like '%http:%' or lookfor like '%https:%' or lookfor like '%mailto:%' or length(lookfor) > 256");

			int rowsRemoved2 = removeSearchStmt2.executeUpdate();

			processLog.addNote("Removed " + rowsRemoved2 + " spammy searches");
			processLog.incUpdated();

			processLog.saveToDatabase(vufindConn, logger);
		} catch (SQLException e) {
			processLog.incErrors();
			processLog.addNote("Unable to delete spammy searches. " + e.toString());
			logger.error("Error deleting spammy searches", e);
			processLog.saveToDatabase(vufindConn, logger);
		}

		//Remove long searches
		try {
			PreparedStatement removeSearchStmt = vufindConn.prepareStatement("DELETE from search_stats_new where length(phrase) > 256");

			int rowsRemoved = removeSearchStmt.executeUpdate();

			processLog.addNote("Removed " + rowsRemoved + " long searches");
			processLog.incUpdated();

			processLog.saveToDatabase(vufindConn, logger);
		} catch (SQLException e) {
			processLog.incErrors();
			processLog.addNote("Unable to delete long searches. " + e.toString());
			logger.error("Error deleting long searches", e);
			processLog.saveToDatabase(vufindConn, logger);
		}

		cleanupReadingHistory(vufindConn, logger, processLog);

		//Remove indexing reports
		try{
			//Get the data directory where reports are stored
			File dataDir = new File(configIni.get("Reindex", "marcPath"));
			dataDir = dataDir.getParentFile();
			//Get a list of dates that should be kept
			SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd");
			GregorianCalendar today = new GregorianCalendar();
			ArrayList<String> validDatesToKeep = new ArrayList<>();
			//Keep the last 7 days
			for (int i = 0; i < 7; i++) {
				validDatesToKeep.add(dateFormatter.format(today.getTime()));
				today.add(Calendar.DATE, -1);
			}
			//Keep the last 12 months
			today.setTime(new Date());
			today.set(Calendar.DAY_OF_MONTH, 1);
			for (int i = 0; i < 12; i++) {
				validDatesToKeep.add(dateFormatter.format(today.getTime()));
				today.add(Calendar.MONTH, -1);
			}

			//List all csv files in the directory
			File[] filesToCheck = dataDir.listFiles(new FilenameFilter() {
				@Override
				public boolean accept(File dir, String name) {
					return name.matches(".*\\d{4}-\\d{2}-\\d{2}\\.csv");
				}
			});

			//Check to see if we should keep or delete the file
			Pattern getDatePattern = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})", Pattern.CANON_EQ);
			for (File curFile : filesToCheck){
				//Get the date from the file
				Matcher fileMatcher = getDatePattern.matcher(curFile.getName());
				if (fileMatcher.find()) {
					String date = fileMatcher.group();
					if (!validDatesToKeep.contains(date)){
						curFile.delete();
					}
				}
			}

		} catch (Exception e){
			processLog.incErrors();
			processLog.addNote("Error removing old indexing reports. " + e.toString());
			logger.error("Error removing old indexing reports", e);
			processLog.saveToDatabase(vufindConn, logger);
		}

		processLog.setFinished();
		processLog.saveToDatabase(vufindConn, logger);
	}

	protected void cleanupReadingHistory(Connection vufindConn, Logger logger, CronProcessLogEntry processLog) {
		//Remove reading history entries that are duplicate based on being renewed
		//Get a list of duplicate titles
		try {
			//Add a filter so that we are looking at 1 week resolution rather than exact.
			PreparedStatement duplicateRecordsToPreserveStmt = vufindConn.prepareStatement("SELECT COUNT(id) as numRecords, userId, groupedWorkPermanentId, source, sourceId, FLOOR(checkOutDate/604800) as checkoutWeek , MIN(id) as idToPreserve FROM user_reading_history_work where deleted = 0 GROUP BY userId, groupedWorkPermanentId, FLOOR(checkOutDate/604800) having numRecords > 1", ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
			PreparedStatement deleteDuplicateRecordStmt = vufindConn.prepareStatement("UPDATE user_reading_history_work SET deleted = 1 WHERE userId = ? AND groupedWorkPermanentId = ? AND FLOOR(checkOutDate/604800) = ? AND id != ?");
			ResultSet duplicateRecordsRS = duplicateRecordsToPreserveStmt.executeQuery();
			int numDuplicateRecords = 0;
			while (duplicateRecordsRS.next()){
				deleteDuplicateRecordStmt.setLong(1, duplicateRecordsRS.getLong("userId"));
				deleteDuplicateRecordStmt.setString(2, duplicateRecordsRS.getString("groupedWorkPermanentId"));
				deleteDuplicateRecordStmt.setLong(3, duplicateRecordsRS.getLong("checkoutWeek"));
				deleteDuplicateRecordStmt.setLong(4, duplicateRecordsRS.getLong("idToPreserve"));
				deleteDuplicateRecordStmt.executeUpdate();

				//int numDeletions = deleteDuplicateRecordStmt.executeUpdate();
				/*if (numDeletions == 0){
					//This happens if the items have already been marked as deleted
					logger.debug("Warning did not delete any records for user " + duplicateRecordsRS.getLong("userId"));
				}*/
				numDuplicateRecords++;
			}
			processLog.addNote("Removed " + numDuplicateRecords + " records that were duplicates (check 1)");

			//Now look for additional duplicates where the check in date is within a week
			duplicateRecordsToPreserveStmt = vufindConn.prepareStatement("SELECT COUNT(id) as numRecords, userId, groupedWorkPermanentId, source, sourceId, FLOOR(checkInDate/604800) checkInWeek, MIN(id) as idToPreserve FROM user_reading_history_work where deleted = 0 GROUP BY userId, groupedWorkPermanentId, FLOOR(checkInDate/604800) having numRecords > 1", ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
			deleteDuplicateRecordStmt = vufindConn.prepareStatement("UPDATE user_reading_history_work SET deleted = 1 WHERE userId = ? AND groupedWorkPermanentId = ? AND FLOOR(checkInDate/604800) = ? AND id != ?");
			duplicateRecordsRS = duplicateRecordsToPreserveStmt.executeQuery();
			numDuplicateRecords = 0;
			while (duplicateRecordsRS.next()){
				deleteDuplicateRecordStmt.setLong(1, duplicateRecordsRS.getLong("userId"));
				deleteDuplicateRecordStmt.setString(2, duplicateRecordsRS.getString("groupedWorkPermanentId"));
				deleteDuplicateRecordStmt.setLong(3, duplicateRecordsRS.getLong("checkInWeek"));
				deleteDuplicateRecordStmt.setLong(4, duplicateRecordsRS.getLong("idToPreserve"));
				deleteDuplicateRecordStmt.executeUpdate();

				//int numDeletions = deleteDuplicateRecordStmt.executeUpdate();
				/*if (numDeletions == 0){
					//This happens if the items have already been marked as deleted
					logger.debug("Warning did not delete any records for user " + duplicateRecordsRS.getLong("userId"));
				}*/
				numDuplicateRecords++;
			}
			processLog.addNote("Removed " + numDuplicateRecords + " records that were duplicates (check 2)");
		} catch (SQLException e) {
			processLog.incErrors();
			processLog.addNote("Unable to delete duplicate reading history entries. " + e.toString());
			logger.error("Error deleting duplicate reading history entries", e);
			processLog.saveToDatabase(vufindConn, logger);
		}

		//Remove invalid reading history entries
		try{
			PreparedStatement removeInvalidReadingHistoryEntriesStmt = vufindConn.prepareStatement("DELETE FROM user_reading_history_work WHERE groupedWorkPermanentId = 'L'");
			int numUpdates = removeInvalidReadingHistoryEntriesStmt.executeUpdate();
			processLog.addNote("Removed " + numUpdates + " invalid reading history entries");
			processLog.incUpdated();

		} catch (Exception e){
			processLog.incErrors();
			processLog.addNote("Error removing invalid reading history entriee. " + e.toString());
			logger.error("Error removing invalid reading history entriee", e);
			processLog.saveToDatabase(vufindConn, logger);
		}
	}

}
