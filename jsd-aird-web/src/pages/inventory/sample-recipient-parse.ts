export interface RecipientInfo {
  name?: string;
  phone?: string;
  address?: string;
}

export function parseRecipient(text: string): RecipientInfo {
  const lines = text.replace(/\r/g, '').split(/\n|[；;]/).map((line) => line.trim()).filter(Boolean);
  const phone = text.match(/(?<!\d)1[3-9]\d{9}(?!\d)/)?.[0];
  const labeled = (pattern: RegExp) => lines.map((line) => line.match(pattern)?.[1]?.trim()).find(Boolean);
  const name = labeled(/(?:收件人|联系人|姓名)[：:\s]+([^\s，,：:]+)/)
    ?? lines.find((line) => !/\d|省|市|区|县|路|街|号|公司|集团/.test(line) && line.length >= 2 && line.length <= 5)
    ?? text.match(/(?:^|\n|\s)([\u4e00-\u9fa5]{2,4})[\s，,、]+1[3-9]\d{9}/)?.[1];
  const explicitAddress = labeled(/(?:收件地址|寄送地址|地址)[：:\s]+(.+)/);
  const addressLine = lines.find((line) => /(?:省|市|自治区).*(?:区|县|市|路|街|镇|号)/.test(line));
  const address = explicitAddress ?? addressLine?.replace(phone ?? '\u0000', '').replace(name ?? '\u0000', '').replace(/^[\s，,、]+/, '').trim();
  return { name, phone, address };
}
