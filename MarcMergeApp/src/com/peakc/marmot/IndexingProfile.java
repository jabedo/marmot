package com.peakc.marmot;

/**
 * A copy of indexing profile information from the database
 *
 * Pika
 * User: Mark Noble
 * Date: 6/30/2015
 * Time: 10:38 PM
 */
public class IndexingProfile {
	public Long id;
	public String name;
	public String marcPath;
	public String marcEncoding;
	public String individualMarcPath;
	public String recordNumberTag;
	private String recordNumberPrefix;
	private char eContentDescriptor = ' ';
	private String itemTag;

	public String getRecordNumberTag() {
		return recordNumberTag;
	}

	public void setRecordNumberTag(String recordNumberTag) {
		this.recordNumberTag = recordNumberTag;
	}


	public String getRecordNumberPrefix() {
		return recordNumberPrefix;
	}

	public void setRecordNumberPrefix(String recordNumberPrefix) {
		this.recordNumberPrefix = recordNumberPrefix;
	}

	public boolean useEContentSubfield() {
		return this.eContentDescriptor != ' ';
	}

	public String getItemTag() {
		return itemTag;
	}

	public void setItemTag(String itemTag) {
		this.itemTag = itemTag;
	}

	public char getEContentDescriptor() {
		return eContentDescriptor;
	}

	public void setEContentDescriptor(char eContentDescriptor) {
		this.eContentDescriptor = eContentDescriptor;
	}
}
