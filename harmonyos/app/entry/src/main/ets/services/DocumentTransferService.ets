import { common } from '@kit.AbilityKit';
import { util } from '@kit.ArkTS';
import { fileIo, picker } from '@kit.CoreFileKit';

const IMPORT_READ_CHUNK_BYTES = 64 * 1024;

export class DocumentTransferService {
  private context: common.UIAbilityContext;

  constructor(context: common.UIAbilityContext) {
    this.context = context;
  }

  async exportJson(fileName: string, body: string): Promise<string | undefined> {
    const viewPicker = new picker.DocumentViewPicker(this.context);
    const options = new picker.DocumentSaveOptions();
    options.newFileNames = [fileName];
    options.fileSuffixChoices = ['JSON|.json'];
    const uris = await viewPicker.save(options);
    const uri = uris[0];
    if (!uri) return undefined;
    const file = fileIo.openSync(uri, fileIo.OpenMode.WRITE_ONLY | fileIo.OpenMode.TRUNC | fileIo.OpenMode.SYNC);
    try {
      fileIo.writeSync(file.fd, body);
      fileIo.fsyncSync(file.fd);
    } finally {
      fileIo.closeSync(file);
    }
    return uri;
  }

  async importJson(maxBytes: number): Promise<string | undefined> {
    const viewPicker = new picker.DocumentViewPicker(this.context);
    const options = new picker.DocumentSelectOptions();
    options.maxSelectNumber = 1;
    options.fileSuffixFilters = ['JSON|.json'];
    const uris = await viewPicker.select(options);
    const uri = uris[0];
    if (!uri) return undefined;

    const file = fileIo.openSync(uri, fileIo.OpenMode.READ_ONLY);
    try {
      const size = fileIo.statSync(file.fd).size;
      if (!Number.isSafeInteger(maxBytes) || maxBytes <= 0 || !Number.isSafeInteger(size) ||
        size <= 0 || size > maxBytes) {
        throw new Error(`导入文件必须是 1 至 ${maxBytes} 字节的 JSON 文档`);
      }

      const bytes = new Uint8Array(size);
      let offset = 0;
      while (offset < size) {
        const chunkSize = Math.min(IMPORT_READ_CHUNK_BYTES, size - offset);
        const chunkBuffer = new ArrayBuffer(chunkSize);
        const bytesRead = fileIo.readSync(file.fd, chunkBuffer, { length: chunkSize });
        if (bytesRead <= 0 || bytesRead > chunkSize) {
          throw new Error('导入文件读取不完整');
        }
        bytes.set(new Uint8Array(chunkBuffer, 0, bytesRead), offset);
        offset += bytesRead;
      }
      return util.TextDecoder.create('utf-8', { fatal: true }).decodeToString(bytes);
    } finally {
      fileIo.closeSync(file);
    }
  }
}
