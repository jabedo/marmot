package com.peakc.marmot;

import org.apache.log4j.Logger;
import org.ini4j.Ini;
import org.ini4j.Profile.Section;

import java.sql.Connection;

public interface IProcessHandler {
	public void doCronProcess(String servername, Ini configIni, Section processSettings, Connection vufindConn, Connection econtentConn, CronLogEntry cronEntry, Logger logger );

}
