import React, { ChangeEvent, useState } from 'react';
import * as Tabs from '@radix-ui/react-tabs';
import { dateMillisToString } from '../../api/Util';
import { uploadResource } from '../../api/generated';
import DialogWindow from '../common/DialogWindow';
import './ConfigMenu.scss';
import './FileUpload.scss';

const DEFAULT_BUCKET_ID = 'media';

type UploadStatus = 'idle' | 'uploading' | 'success' | 'error';

const FileUpload = () => {
  
    const [file, setFile] = useState<File | undefined>(undefined);
    const [status, setStatus] = useState<UploadStatus>('idle');
    const [progress, setProgress] = useState<number>(0);
    const [feedback, setFeedback] = useState<string | undefined>(undefined);

    const onFileChange = (event: ChangeEvent<HTMLInputElement>) => {
      if (event.target.files && event.target.files[0]) {
        setFile(event.target.files[0]);
        setStatus('idle');
        setFeedback(undefined);
        setProgress(0);
      }    
    };
    
    const onFileUpload = async () => {
      if (!file) return;

      setStatus('uploading');
      setFeedback(undefined);
      setProgress(0);

      try {
        await uploadResource(DEFAULT_BUCKET_ID, file, {
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

        setStatus('success');
        setFeedback('Upload complete!');
        setFile(undefined);
        setProgress(0);
      } catch {
        setStatus('error');
        setFeedback('Upload failed');
      }
    };
    
    return (
      <DialogWindow>
        <Tabs.Root defaultValue="upload">
          <Tabs.List className="tabs-list">
            <Tabs.Trigger className="tab-trigger" value="upload">Upload</Tabs.Trigger>
          </Tabs.List>

          <Tabs.Content className="tab-content file-upload-tab-content" value="upload">
            <div className="config-form">
              <div className="form-section">
                <p className="form-label">File</p>
                <div className="form-content">
                  <input
                    type="file"
                    className="file-upload-input"
                    accept=".mp4,video/*,image/*"
                    onChange={onFileChange}
                    disabled={status === 'uploading'}
                  />
                </div>
              </div>

              {file && (
                <>
                  <div className="form-section">
                    <p className="form-label">Name</p>
                    <div className="form-content">{file.name}</div>
                  </div>
                  <div className="form-section">
                    <p className="form-label">Type</p>
                    <div className="form-content">{file.type || "-"}</div>
                  </div>
                  <div className="form-section">
                    <p className="form-label">Size</p>
                    <div className="form-content">{(file.size / (1024 * 1024)).toFixed(2)} MB</div>
                  </div>
                  <div className="form-section">
                    <p className="form-label">Last modified</p>
                    <div className="form-content">{dateMillisToString(file.lastModified)}</div>
                  </div>
                </>
              )}

              {status === 'uploading' && (
                <div className="form-section">
                  <p className="form-label">Progress</p>
                  <div className="form-content">
                    <div className="progress-container">
                      <div className="progress-bar">
                        <div className="progress-fill" style={{ width: `${progress}%` }} />
                      </div>
                      <span className="progress-text">{progress}%</span>
                    </div>
                  </div>
                </div>
              )}

              {feedback && (
                <div className="form-section">
                  <p className="form-label">Status</p>
                  <div className="form-content">
                    <span className={`feedback ${status === 'error' ? 'feedback-error' : 'feedback-success'}`}>
                      {feedback}
                    </span>
                  </div>
                </div>
              )}
            </div>
          </Tabs.Content>
        </Tabs.Root>

        <button
          className="abs-bottom-right button-primary"
          onClick={onFileUpload}
          disabled={!file || status === 'uploading'}
        >
          {status === 'uploading' ? 'Uploading...' : 'Upload'}
        </button>
      </DialogWindow>
    );
  }
 
export default FileUpload;
