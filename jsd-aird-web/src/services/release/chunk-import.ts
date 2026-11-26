import { isChunkError } from './build-recovery';

/** Retry transient module/CSS downloads, never application evaluation errors. */
export async function retryChunkImport<T>(importer: () => Promise<T>): Promise<T> {
  for (let attempt = 0; ; attempt++) {
    try { return await importer(); }
    catch (error) {
      if (!isChunkError(error) || attempt >= 2) throw error;
      await new Promise(resolve => setTimeout(resolve, attempt === 0 ? 100 : 300));
    }
  }
}
