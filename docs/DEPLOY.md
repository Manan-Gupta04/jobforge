# Deploying JobForge for free (Render + Neon)

Result: a public URL such as `https://jobforge.onrender.com` running the dashboard, API docs and
the AI pipeline demo, backed by a persistent PostgreSQL database. Total cost: $0.

The free Render tier sleeps after 15 minutes without traffic and takes about a minute to wake,
which is fine for a portfolio link. The database lives on Neon, so jobs and workflows survive
restarts.

## 1. Create the database on Neon

1. Sign up at https://neon.tech with GitHub.
2. Create a project named `jobforge` (any region; pick the one closest to Render's region you will use).
3. On the project dashboard click **Connect**, choose **Java**, and note the three values in the connection string:
   - host, for example `ep-cool-name-123456.us-east-2.aws.neon.tech`
   - database name, usually `neondb`
   - user and password (click "Show password")

The JDBC URL JobForge needs is:

```
jdbc:postgresql://<host>/<database>?sslmode=require
```

## 2. Deploy on Render

1. Sign up at https://render.com with GitHub and authorise access to the `jobforge` repository.
2. Click **New → Blueprint**, pick the `jobforge` repo. Render reads `render.yaml` and shows one web service.
3. Before clicking Apply, fill in the environment variables it asks for:

   | Variable | Value |
   |---|---|
   | `JOBFORGE_DB_URL` | the JDBC URL from step 1 |
   | `JOBFORGE_DB_USER` | Neon user |
   | `JOBFORGE_DB_PASSWORD` | Neon password |
   | `ANTHROPIC_API_KEY` | leave **empty** for the public demo (see below) |

4. Click **Apply**. The first build takes 5 to 8 minutes (Maven downloads inside the Docker build). Watch the **Logs** tab for `Started JobForgeApplication`.
5. Open the URL Render shows. The dashboard should say `LLM mock (1/s)` in the header.

## 3. Verify

```bash
curl -s https://<your-app>.onrender.com/api/v1/stats
curl -s -X POST https://<your-app>.onrender.com/api/v1/workflows/examples/content-pipeline \
     -H 'Content-Type: application/json' -d '{"url":"https://en.wikipedia.org/wiki/Job_scheduler"}'
```

Then open the dashboard and watch the pipeline's steps turn green.

## About the API key on a public demo

JobForge picks the LLM provider automatically: with `ANTHROPIC_API_KEY` set it calls Claude, without
it a deterministic mock answers instantly. For a **public** link, leave the key unset. Anyone can
submit jobs to the demo, and a real key would let strangers spend your credits. The mock still
shows everything the engine does: dependencies, templating, retries, rate limiting and recovery.

If you want real Claude output for a live interview demo, add the key for the duration of the
demo and remove it afterwards. The blueprint already caps output tokens at 600 and limits LLM
jobs to one per second per node, which bounds the worst-case spend.

## Keeping it awake (optional)

Free services sleep when idle. If you want the link to open instantly, use a free uptime monitor
such as https://uptimerobot.com to ping `/actuator/health` every 10 minutes.

## Alternative: Docker anywhere

Any host that runs a Docker image works the same way: build with the `Dockerfile`, set
`SPRING_PROFILES_ACTIVE=postgres` and the three `JOBFORGE_DB_*` variables, expose port 8080.
