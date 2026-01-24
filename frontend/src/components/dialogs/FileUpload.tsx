import React, { ChangeEvent, useState } from 'react';
import { dateMillisToString } from '../../api/Util';
import Dialog from '../common/Dialog';
import './FileUpload.scss';

const UPLOAD_ENDPOINT = '/api/resources/media/upload';

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
    
    const onFileUpload = () => {
      if (!file) return;

      const formData = new FormData();
      formData.append('file', file);

      const xhr = new XMLHttpRequest();
      
      xhr.upload.addEventListener('progress', (event) => {
        if (event.lengthComputable) {
          const percentComplete = Math.round((event.loaded / event.total) * 100);
          setProgress(percentComplete);
        }
      });

      xhr.addEventListener('load', () => {
        if (xhr.status >= 200 && xhr.status < 300) {
          setStatus('success');
          setFeedback('Upload complete!');
          setFile(undefined);
          setProgress(0);
        } else {
          setStatus('error');
          setFeedback(`Upload failed: ${xhr.statusText || 'Unknown error'}`);
        }
      });

      xhr.addEventListener('error', () => {
        setStatus('error');
        setFeedback('Upload failed: Network error');
      });

      setStatus('uploading');
      setFeedback(undefined);
      xhr.open('POST', UPLOAD_ENDPOINT);
      xhr.send(formData);
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