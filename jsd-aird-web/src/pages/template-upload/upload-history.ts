/** Internal editor snapshots are not original files uploaded by users. */
export function isUserUploadHistoryFile(fileName: string): boolean {
  return !/(?:xlsx|xls|csv|docx|doc)-univer-snapshot\.json$/i.test(fileName.trim());
}
