declare module 'mammoth/mammoth.browser' {
  interface MammothResult {
    value: string;
    messages: unknown[];
  }
  interface ConvertOptions {
    arrayBuffer?: ArrayBuffer;
    buffer?: ArrayBuffer;
  }
  const mammoth: {
    convertToHtml(input: ConvertOptions): Promise<MammothResult>;
  };
  export default mammoth;
}