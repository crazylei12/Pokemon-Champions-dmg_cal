import { common } from '@kit.AbilityKit';
import { fileIo, picker } from '@kit.CoreFileKit';

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
    const size = fileIo.statSync(uri).size;
    if (!Number.isSafeInteger(maxBytes) || maxBytes <= 0 || size <= 0 || size > maxBytes) {
      throw new Error(`导入文件必须是 1 至 ${maxBytes} 字节的 JSON 文档`);
    }
    return fileIo.readTextSync(uri, { encoding: 'utf-8' });
  }
}
