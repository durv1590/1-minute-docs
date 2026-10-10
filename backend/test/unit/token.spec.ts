import { TokenService } from '../../src/modules/auth/token.service';
import { testConfig } from '../helpers';

describe('TokenService', () => {
  const svc = new TokenService(testConfig());

  it('issues verifiable tokens bound to a random client id', () => {
    const a = svc.issue();
    const b = svc.issue();
    expect(a.clientId).not.toEqual(b.clientId);
    expect(svc.verify(a.token)?.sub).toEqual(a.clientId);
  });

  it('rejects tampered payloads and signatures', () => {
    const { token } = svc.issue();
    const [body, sig] = token.split('.');
    const forged = Buffer.from(JSON.stringify({ ...JSON.parse(Buffer.from(body, 'base64url').toString()), sub: '00000000-0000-4000-8000-000000000000' })).toString('base64url');
    expect(svc.verify(`${forged}.${sig}`)).toBeNull();
    expect(svc.verify(`${body}.${sig.slice(0, -2)}xx`)).toBeNull();
    expect(svc.verify('garbage')).toBeNull();
    expect(svc.verify('a'.repeat(1000))).toBeNull();
  });

  it('rejects tokens signed with another secret', () => {
    const other = new TokenService(testConfig({ TOKEN_SECRET: 'another-secret-another-secret-0123456789' }));
    expect(svc.verify(other.issue().token)).toBeNull();
  });

  it('rejects expired tokens', () => {
    const { token } = svc.issue(Date.now() - 31 * 86400_000);
    expect(svc.verify(token)).toBeNull();
  });
});
