import { App } from 'aws-cdk-lib';
import CloudflarePublicHostnameStack from './CloudflarePublicHostnameStack';

const app = new App();

new CloudflarePublicHostnameStack(app, 'cloudflare-public-hostname-lambda', {});
