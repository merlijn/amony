import React, { ChangeEvent, useState } from 'react';
import { dateMillisToString } from '../../api/Util';
import { uploadResource } from '../../api/generated';
import Dialog from '../common/Dialog';
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
      <Dialog title="Upload media">
        <div className="file-upload-content">
          <div className="file-input-container">
            <input 
              type="file" 
              accept=".mp4,video/*,image/*"
              onChange={onFileChange}
              disabled={status === 'uploading'}
            />
          </div>
          
          {file && (
            <div className="file-info">
              <p><strong>File:</strong> {file.name}</p>
              <p><strong>Type:</strong> {file.type}</p>
              <p><strong>Size:</strong> {(file.size / (1024 * 1024)).toFixed(2)} MB</p>
              <p><strong>Last Modified:</strong> {dateMillisToString(file.lastModified)}</p>
            </div>
          )}

          {status === 'uploading' && (
            <div className="progress-container">
              <div className="progress-bar">
                <div className="progress-fill" style={{ width: `${progress}%` }} />
              </div>
              <span className="progress-text">{progress}%</span>
            </div>
          )}

          {feedback && (
            <div className={`feedback ${status === 'error' ? 'feedback-error' : 'feedback-success'}`}>
              {feedback}
            </div>
          )}

          <button 
            className="abs-bottom-right button-primary" 
            onClick={onFileUpload}
            disabled={!file || status === 'uploading'}
          >
            {status === 'uploading' ? 'Uploading...' : 'Upload'}
          </button>
        </div>
      </Dialog>
    );
  }
 
export default FileUpload;