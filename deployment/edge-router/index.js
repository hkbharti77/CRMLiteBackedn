/**
 * CRMLite Multi-Region Webhook Router
 * (Cloudflare Worker Implementation)
 * 
 * This router acts as the single entry point for Meta WhatsApp Webhooks.
 * It identifies the tenant region and forwards the request to the appropriate regional cluster.
 */

const REGION_MAPPING = {
  // mapping of phone_number_id -> regional_endpoint
  '123456789': 'https://us-east.api.crmlite.com',
  '987654321': 'https://eu-west.api.crmlite.com',
};

const DEFAULT_REGION = 'https://us-east.api.crmlite.com';

export default {
  async fetch(request, env, ctx) {
    if (request.method === 'GET') {
      // Handle WhatsApp Verification (hub.verify_token)
      return forwardToRegion(request, DEFAULT_REGION);
    }

    if (request.method === 'POST') {
      const payload = await request.clone().json();
      
      try {
        const phoneNumberId = payload.entry?.[0]?.changes?.[0]?.value?.metadata?.phone_number_id;
        
        // Lookup region from KV or static mapping
        // const regionUrl = await env.REGION_STORE.get(phoneNumberId) || DEFAULT_REGION;
        const regionUrl = REGION_MAPPING[phoneNumberId] || DEFAULT_REGION;
        
        console.log(`Routing phoneNumberId ${phoneNumberId} to ${regionUrl}`);
        
        return forwardToRegion(request, regionUrl);
      } catch (e) {
        console.error('Error parsing webhook payload', e);
        return forwardToRegion(request, DEFAULT_REGION);
      }
    }

    return new Response('Method not allowed', { status: 405 });
  }
};

async function forwardToRegion(request, regionUrl) {
  const url = new URL(request.url);
  const targetUrl = `${regionUrl}${url.pathname}${url.search}`;
  
  const modifiedRequest = new Request(targetUrl, {
    method: request.method,
    headers: request.headers,
    body: request.body,
    redirect: 'follow'
  });

  return fetch(modifiedRequest);
}
