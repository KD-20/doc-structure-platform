/**
 * Catches the "selected pdf, uploaded a jpeg" mistake at upload time. Only applies when the
 * chosen doc type is one of the system's own extension-derived fallback categories (or a
 * common alias of one) — free-form business types like "invoice"/"resume"/"receipt" are
 * legitimately format-agnostic (an invoice can be a PDF or a scanned image) and are
 * intentionally left unvalidated. Mirrors DocTypeClassifier#classifyByExtension on the
 * backend so "what counts as a PDF" stays a single definition, not two that can drift apart.
 */

const FORMAT_LABELS: Record<string, string> = {
  pdf_document: "PDF",
  word_document: "Word document",
  spreadsheet: "spreadsheet",
  presentation: "presentation",
  image: "image",
  text_document: "text file",
  web_document: "HTML file",
};

const FORMAT_ALIASES: Record<string, string> = {
  pdf: "pdf_document",
  pdf_document: "pdf_document",
  word: "word_document",
  word_document: "word_document",
  doc: "word_document",
  docx: "word_document",
  spreadsheet: "spreadsheet",
  excel: "spreadsheet",
  xls: "spreadsheet",
  xlsx: "spreadsheet",
  presentation: "presentation",
  ppt: "presentation",
  pptx: "presentation",
  image: "image",
  img: "image",
  photo: "image",
  text: "text_document",
  text_document: "text_document",
  txt: "text_document",
  html: "web_document",
  web_document: "web_document",
};

export function formatCategoryForDocType(docType: string): string | null {
  return FORMAT_ALIASES[docType.trim().toLowerCase()] ?? null;
}

export function formatCategoryForFilename(filename: string): string | null {
  const match = filename.match(/\.([a-zA-Z0-9]{1,6})$/);
  if (!match) return null;
  switch (match[1].toLowerCase()) {
    case "pdf":
      return "pdf_document";
    case "doc":
    case "docx":
    case "rtf":
    case "odt":
      return "word_document";
    case "xls":
    case "xlsx":
    case "csv":
    case "ods":
      return "spreadsheet";
    case "ppt":
    case "pptx":
    case "odp":
      return "presentation";
    case "jpg":
    case "jpeg":
    case "png":
    case "gif":
    case "bmp":
    case "tiff":
    case "tif":
    case "webp":
    case "heic":
      return "image";
    case "txt":
    case "md":
      return "text_document";
    case "html":
    case "htm":
      return "web_document";
    default:
      return null;
  }
}

export function formatLabel(category: string): string {
  return FORMAT_LABELS[category] ?? category;
}
