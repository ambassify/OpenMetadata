/*
 *  Copyright 2021 Collate
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

import { AxiosError } from 'axios';
import { LoadingState } from 'Models';
import React, { FunctionComponent, useEffect, useState } from 'react';
import { useHistory, useParams } from 'react-router-dom';
import {
  deleteWebhook,
  getWebhookByName,
  updateWebhook,
} from '../../axiosAPIs/webhookAPI';
import AddWebhook from '../../components/AddWebhook/AddWebhook';
import PageContainerV1 from '../../components/containers/PageContainerV1';
import Loader from '../../components/Loader/Loader';
import { ROUTES } from '../../constants/constants';
import { FormSubmitType } from '../../enums/form.enum';
import { CreateWebhook } from '../../generated/api/events/createWebhook';
import { Webhook } from '../../generated/entity/events/webhook';
import { useAuth } from '../../hooks/authHooks';
import useToastContext from '../../hooks/useToastContext';

const EditWebhookPage: FunctionComponent = () => {
  const { webhookName } = useParams<{ [key: string]: string }>();
  const { isAuthDisabled, isAdminUser } = useAuth();
  const history = useHistory();
  const showToast = useToastContext();
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [webhookData, setWebhookData] = useState<Webhook>();
  const [status, setStatus] = useState<LoadingState>('initial');
  const [deleteStatus, setDeleteStatus] = useState<LoadingState>('initial');

  const fetchWebhook = () => {
    setIsLoading(true);
    getWebhookByName(webhookName)
      .then((res) => {
        setWebhookData(res.data);
      })
      .catch((err: AxiosError) => {
        showToast({
          variant: 'error',
          body: err.message || 'Something went wrong!',
        });
      })
      .finally(() => setIsLoading(false));
  };

  const goToWebhooks = () => {
    history.push(ROUTES.WEBHOOKS);
  };

  const handleCancel = () => {
    goToWebhooks();
  };

  const handleSave = (data: CreateWebhook) => {
    setStatus('waiting');
    const { name, secretKey } = webhookData || data;
    updateWebhook({ ...data, name, secretKey })
      .then(() => {
        setStatus('success');
        setTimeout(() => {
          setStatus('initial');
          goToWebhooks();
        }, 500);
      })
      .catch((err: AxiosError) => {
        showToast({
          variant: 'error',
          body: err.message || 'Something went wrong!',
        });
        setStatus('initial');
      });
  };

  const handleDelete = (id: string) => {
    setDeleteStatus('waiting');
    deleteWebhook(id)
      .then(() => {
        setDeleteStatus('initial');
        goToWebhooks();
      })
      .catch((err: AxiosError) => {
        showToast({
          variant: 'error',
          body: err.message || 'Something went wrong!',
        });
        setDeleteStatus('initial');
      });
  };

  useEffect(() => {
    fetchWebhook();
  }, []);

  return (
    <PageContainerV1>
      {!isLoading ? (
        <AddWebhook
          allowAccess={isAdminUser || isAuthDisabled}
          data={webhookData}
          deleteState={deleteStatus}
          header="Edit Webhook"
          mode={FormSubmitType.EDIT}
          saveState={status}
          onCancel={handleCancel}
          onDelete={handleDelete}
          onSave={handleSave}
        />
      ) : (
        <Loader />
      )}
    </PageContainerV1>
  );
};

export default EditWebhookPage;
