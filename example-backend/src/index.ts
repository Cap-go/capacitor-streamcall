import { serve } from '@hono/node-server';
import { StreamClient, type UserRequest } from '@stream-io/node-sdk';
import dotenv from 'dotenv';
import { Hono } from 'hono';
import { cors } from 'hono/cors';

// Load environment variables from .env file
dotenv.config();

const app = new Hono();

// Enable CORS
app.use(
  '/*',
  cors({
    origin: '*', // In production, you should specify your actual frontend domain
    allowMethods: ['GET', 'POST', 'PUT', 'DELETE', 'OPTIONS'],
    allowHeaders: ['Content-Type', 'Authorization', 'magic-shit'],
    exposeHeaders: ['Content-Length', 'X-Requested-With'],
    maxAge: 86400,
    credentials: true,
  }),
);

// Check for required environment variables
if (!process.env.STREAM_API_KEY || !process.env.STREAM_API_SECRET) {
  console.error('Error: STREAM_API_KEY and STREAM_API_SECRET must be set in .env file');
  process.exit(1);
}

const apiKey = process.env.STREAM_API_KEY;
const apiKeyDev = process.env.STREAM_API_KEY_DEV;
const apiSecret = process.env.STREAM_API_SECRET;
const apiSecretDev = process.env.STREAM_API_SECRET_DEV;
const validity = 60 * 60 * 6; // Six hours in seconds
const client = new StreamClient(apiKey, apiSecret, {
  timeout: 10000,
});
const devClient = new StreamClient(apiKeyDev, apiSecretDev, {
  timeout: 10000,
});

app.get('/user', async (c) => {
  try {
    const userId = c.req.query('user_id');
    const team = c.req.query('team');
    const environment = c.req.query('environment');

    if (!userId) {
      return c.json({ error: 'user_id query parameter is required' }, 400);
    }

    const userName = `User ${userId}`;
    const imageURL = 'https://getstream.io/static/2796a305dd07651fcceb4721a94f4505/a3911/martin-mitrevski.webp';

    const newUser: UserRequest = {
      id: userId,
      role: 'user',
      name: userName,
      image: imageURL,
    };

    if (team) {
      newUser.teams = [team];
    }

    console.log('upsertUsers', newUser);
    const clientToUse = environment === 'dev' ? devClient : client;
    await clientToUse.upsertUsers([newUser]);
    const token = clientToUse.generateUserToken({ user_id: userId, validity_in_seconds: validity });

    console.log('token', token);
    return c.json({
      token,
      userId,
      name: userName,
      imageURL,
      teams: newUser.teams,
    });
  } catch (error) {
    console.error('Error fetching user:', error);
    return c.json({ error: 'Failed to fetch user' }, 500);
  }
});

app.get('/members', async (c) => {
  const team = c.req.query('team');
  const members = await client.queryUsers({
    payload: {
      filter_conditions: {
        teams: {
          $in: [team],
        },
      },
    },
  });
  return c.json(members);
});

const port = 3763;
console.log(`Server is running on http://localhost:${port}`);

serve({
  fetch: app.fetch,
  port,
});
