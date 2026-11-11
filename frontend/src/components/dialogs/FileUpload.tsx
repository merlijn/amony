import React, { ChangeEvent, useState } from 'react';
import * as Tabs from '@radix-ui/react-tabs';
import { dateMillisToString } from '../../api/Util';
import { uploadResource } from '../../api/generated';
import DialogWindow from '../common/DialogWindow';
import InfoTable, { InfoRow } from '../common/InfoTable';
import { serverErrorMessage, useCommand } from '../common/useCommand';
import './FileUpload.scss';

const DEFAULT_BUCKET_ID = 'media';

type UploadPhase = 'idle' | 'uploading' | 'processing' | 'success' | 'error';

const FileUpload = () => {

    const [file, setFile] = useState<File | undefined>(undefined);
    const [phase, setPhase] = useState<UploadPhase>('idle');
    const [progress, setProgress] = useState<number>(0);
    const [feedback, setFeedback] = useState<string | undefined>(undefined);
    const { run, reset, isRunning } = useCommand();

    const busy = phase === 'uploading' || phase === 'processing' || isRunning;

    const onFileChange = (event: ChangeEvent<HTMLInputElement>) => {
      if (event.target.files && event.target.files[0]) {
        setFile(event.target.files[0]);
        setPhase('idle');
        setFeedback(undefined);
        setProgress(0);
      }
    };

    const onFileUpload = async () => {
      if (!file) return;

      reset();
      setPhase('uploading');
      setFeedback(undefined);
      setProgress(0);

      try {
        const command = await uploadResource(DEFAULT_BUCKET_ID, file, {
          headers: {
            'Content-Type': file.type || 'application/octet-stream',
            'X-Filename': encodeURIComponent(file.name),
          },
          onUploadProgress: (progressEvent) => {
            if (progressEvent.total) {
              const percentComplete = Math.round((progressEvent.loaded / progressEvent.total) * 100);
              setProgress(percentComplete);
            }
          },
        });

        setPhase('processing');
        await run(
          command.commandId,
          () => {
            setPhase('success');
            setFeedback('Upload complete!');
            setFile(undefined);
            setProgress(0);
          },
          (message) => {
            setPhase('error');
            setFeedback(message);
          }
        );
      } catch (cause) {
        setPhase('error');
        setFeedback(serverErrorMessage(cause) || 'Upload failed');
      }
    };

    return (
      <DialogWindow>
        <Tabs.Root defaultValue="upload">
          <Tabs.List className="tabs-list">
            <Tabs.Trigger className="tab-trigger" value="upload">Upload</Tabs.Trigger>
          </Tabs.List>

          <Tabs.Content className="tab-content file-upload-tab-content" value="upload">
            <InfoTable>
              <InfoRow label="File">
                <input
                  type="file"
                  className="file-upload-input"
                  accept=".mp4,video/*,image/*"
                  onChange={onFileChange}
                  disabled={busy}
                />
              </InfoRow>

              {file && (
                <>
                  <InfoRow label="Name">{file.name}</InfoRow>
                  <InfoRow label="Type">{file.type || "-"}</InfoRow>
                  <InfoRow label="Size">{(file.size / (1024 * 1024)).toFixed(2)} MB</InfoRow>
                  <InfoRow label="Last modified">{dateMillisToString(file.lastModified)}</InfoRow>
                </>
              )}

              {phase === 'uploading' && (
                <InfoRow label="Progress">
                  <div className="progress-container">
                    <div className="progress-bar">
                      <div className="progress-fill" style={{ width: `${progress}%` }} />
                    </div>
                    <span className="progress-text">{progress}%</span>
                  </div>
                </InfoRow>
              )}

              {phase === 'processing' && (
                <InfoRow label="Status">
                  <span className="feedback">Processing...</span>
                </InfoRow>
              )}

              {feedback && (
                <InfoRow label="Status">
                  <span className={`feedback ${phase === 'error' ? 'feedback-error' : 'feedback-success'}`}>
                    {feedback}
                  </span>
                </InfoRow>
              )}
            </InfoTable>
          </Tabs.Content>
        </Tabs.Root>

        <button
          className="abs-bottom-right button-primary"
          onClick={onFileUpload}
          disabled={!file || busy}
        >
          {phase === 'uploading' ? 'Uploading...' : phase === 'processing' ? 'Processing...' : 'Upload'}
        </button>
      </DialogWindow>
    );
  }

export default FileUpload;
