# Background worker

This directory is reserved for the Redis Queue (RQ) worker. The worker will process jobs only after an authorized media source has been validated by the API. It must not accept arbitrary URLs or attempt to bypass platform access controls.

Planned responsibilities:

- consume validated job records;
- fetch files through approved provider integrations or user-supplied storage;
- run FFprobe validation;
- optionally remux/transcode authorized files with FFmpeg;
- store outputs in object storage and publish job status.
