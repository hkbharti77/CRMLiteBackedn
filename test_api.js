const axios = require('axios');

async function test() {
  try {
    const loginRes = await axios.post('http://localhost:8080/api/v1/platform/auth/login', {
      email: 'owner@example.com',
      password: 'Admin123!'
    });
    console.log('Login OK');
    const cookie = loginRes.headers['set-cookie'][0];
    
    try {
      const tenantRes = await axios.get('http://localhost:8080/api/v1/platform/tenants?page=0&size=10', {
        headers: { Cookie: cookie }
      });
      console.log('Tenants OK:', tenantRes.data);
    } catch (e) {
      console.log('Tenants Error:', e.response?.status);
      console.log('Tenants Error Data:', e.response?.data);
    }
  } catch(e) {
    console.log('Login Error:', e.message);
  }
}
test();
