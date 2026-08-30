import { JSEncrypt } from 'jsencrypt';

/**
 * 使用后端下发的 RSA 公钥加密密码（PKCS1 v1.5，与后端 RSA/ECB/PKCS1Padding 对应）。
 */
export async function encryptPassword(plain: string): Promise<string> {
  const resp = await fetch('/publicKey');
  const json = await resp.json();
  const publicKey = json.data.publicKey as string;
  const enc = new JSEncrypt();
  enc.setPublicKey(
    `-----BEGIN PUBLIC KEY-----\n${publicKey}\n-----END PUBLIC KEY-----`,
  );
  const encrypted = enc.encrypt(plain);
  if (!encrypted) {
    throw new Error('密码加密失败');
  }
  return encrypted;
}
