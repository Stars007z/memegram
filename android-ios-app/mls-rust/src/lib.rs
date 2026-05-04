use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use openmls::prelude::{tls_codec::*, *};
use openmls_basic_credential::SignatureKeyPair;
use openmls_rust_crypto::OpenMlsRustCrypto;

uniffi::setup_scaffolding!();

const CS: Ciphersuite = Ciphersuite::MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519;

#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum MlsError {
    #[error("{0}")]
    General(String),
}

fn to_err(e: impl std::fmt::Display) -> MlsError {
    MlsError::General(e.to_string())
}

#[derive(uniffi::Record)]
pub struct WelcomeBundle {
    pub commit: Vec<u8>,
    pub welcome: Vec<u8>,
}

#[derive(uniffi::Enum)]
pub enum IncomingMessage {
    Application { data: Vec<u8> },
    CommitApplied,
    Proposal,
    Other,
}

struct MlsKeys {
    provider: OpenMlsRustCrypto,
    credential_with_key: CredentialWithKey,
    signer: SignatureKeyPair,
}

struct ClientInner {
    keys: MlsKeys,
    groups: HashMap<Vec<u8>, MlsGroup>,
}

fn ensure_group_loaded(
    provider: &OpenMlsRustCrypto,
    groups: &mut HashMap<Vec<u8>, MlsGroup>,
    group_id: &[u8],
) -> Result<(), MlsError> {
    if groups.contains_key(group_id) {
        return Ok(());
    }
    let gid = GroupId::from_slice(group_id);
    let group = MlsGroup::load(provider.storage(), &gid)
        .map_err(to_err)?
        .ok_or_else(|| MlsError::General(format!("group {:?} not found", group_id)))?;
    groups.insert(group_id.to_vec(), group);
    Ok(())
}

#[derive(uniffi::Object)]
pub struct MlsClientHandle {
    inner: Mutex<ClientInner>,
}

#[uniffi::export]
impl MlsClientHandle {
    #[uniffi::constructor]
    pub fn new(identity: String) -> Result<Arc<Self>, MlsError> {
        let provider = OpenMlsRustCrypto::default();
        let signer = SignatureKeyPair::new(CS.signature_algorithm()).map_err(to_err)?;
        signer.store(provider.storage()).map_err(to_err)?;

        let credential_with_key = CredentialWithKey {
            credential: BasicCredential::new(identity.into_bytes()).into(),
            signature_key: signer.to_public_vec().into(),
        };

        Ok(Arc::new(Self {
            inner: Mutex::new(ClientInner {
                keys: MlsKeys {
                    provider,
                    credential_with_key,
                    signer,
                },
                groups: HashMap::new(),
            }),
        }))
    }

    #[uniffi::constructor]
    pub fn new_from_state(
        identity: String,
        provider_state: Vec<u8>,
        signing_key_bytes: Vec<u8>,
    ) -> Result<Arc<Self>, MlsError> {
        let pairs: Vec<(Vec<u8>, Vec<u8>)> =
            serde_json::from_slice(&provider_state).map_err(to_err)?;
        let restored_map: HashMap<Vec<u8>, Vec<u8>> = pairs.into_iter().collect();

        let provider = OpenMlsRustCrypto::default();
        {
            let mut values = provider.storage().values.write().map_err(to_err)?;
            *values = restored_map;
        }

        let signer =
            SignatureKeyPair::tls_deserialize_exact_bytes(&signing_key_bytes).map_err(to_err)?;
        signer.store(provider.storage()).map_err(to_err)?;

        let credential_with_key = CredentialWithKey {
            credential: BasicCredential::new(identity.into_bytes()).into(),
            signature_key: signer.to_public_vec().into(),
        };

        Ok(Arc::new(Self {
            inner: Mutex::new(ClientInner {
                keys: MlsKeys {
                    provider,
                    credential_with_key,
                    signer,
                },
                groups: HashMap::new(),
            }),
        }))
    }

    pub fn export_provider_state(&self) -> Result<Vec<u8>, MlsError> {
        let g = self.inner.lock().unwrap();
        let map: HashMap<Vec<u8>, Vec<u8>> = g
            .keys
            .provider
            .storage()
            .values
            .read()
            .map_err(to_err)?
            .clone();
        let pairs: Vec<(&Vec<u8>, &Vec<u8>)> = map.iter().collect();
        serde_json::to_vec(&pairs).map_err(to_err)
    }

    pub fn export_signing_key(&self) -> Result<Vec<u8>, MlsError> {
        let g = self.inner.lock().unwrap();
        g.keys.signer.tls_serialize_detached().map_err(to_err)
    }

    pub fn export_signing_public_key(&self) -> Result<Vec<u8>, MlsError> {
        let g = self.inner.lock().unwrap();
        Ok(g.keys.signer.to_public_vec())
    }

    pub fn generate_key_package(&self) -> Result<Vec<u8>, MlsError> {
        let g = self.inner.lock().unwrap();
        let bundle = KeyPackage::builder()
            .build(
                CS,
                &g.keys.provider,
                &g.keys.signer,
                g.keys.credential_with_key.clone(),
            )
            .map_err(to_err)?;
        bundle
            .key_package()
            .tls_serialize_detached()
            .map_err(to_err)
    }

    pub fn extract_signature_key(&self, key_package_bytes: Vec<u8>) -> Result<Vec<u8>, MlsError> {
        let kp_in: KeyPackageIn =
            KeyPackageIn::tls_deserialize_exact_bytes(&key_package_bytes).map_err(to_err)?;
        Ok(kp_in.unverified_credential().signature_key.as_slice().to_vec())
    }

    pub fn create_group_with_id(self: &Self, group_id: Vec<u8>) -> Result<(), MlsError> {
        let mut g = self.inner.lock().unwrap();
        let cfg = MlsGroupCreateConfig::builder()
            .ciphersuite(CS)
            .use_ratchet_tree_extension(true)
            .build();
        let gid = GroupId::from_slice(&group_id);
        let group = MlsGroup::new_with_group_id(
            &g.keys.provider,
            &g.keys.signer,
            &cfg,
            gid,
            g.keys.credential_with_key.clone(),
        )
        .map_err(to_err)?;
        g.groups.insert(group_id, group);
        Ok(())
    }

    pub fn add_member(
        &self,
        group_id: Vec<u8>,
        key_package_bytes: Vec<u8>,
    ) -> Result<WelcomeBundle, MlsError> {
        let mut g = self.inner.lock().unwrap();

        let ClientInner { keys, groups } = &mut *g;
        ensure_group_loaded(&keys.provider, groups, &group_id)?;

        let kp_in: KeyPackageIn =
            KeyPackageIn::tls_deserialize_exact_bytes(&key_package_bytes).map_err(to_err)?;

        let group = groups
            .get_mut(&group_id)
            .ok_or_else(|| MlsError::General("group not found".into()))?;

        let (commit, welcome, _) = group
            .add_members(&keys.provider, &keys.signer, &[kp_in.into()])
            .map_err(to_err)?;

        Ok(WelcomeBundle {
            commit: commit.tls_serialize_detached().map_err(to_err)?,
            welcome: welcome.tls_serialize_detached().map_err(to_err)?,
        })
    }

    pub fn merge_pending_commit(&self, group_id: Vec<u8>) -> Result<(), MlsError> {
        let mut g = self.inner.lock().unwrap();
        let ClientInner { keys, groups } = &mut *g;
        ensure_group_loaded(&keys.provider, groups, &group_id)?;
        let group = groups
            .get_mut(&group_id)
            .ok_or_else(|| MlsError::General("group not found".into()))?;
        group.merge_pending_commit(&keys.provider).map_err(to_err)?;
        Ok(())
    }

    pub fn clear_pending_commit(&self, group_id: Vec<u8>) -> Result<(), MlsError> {
        let mut g = self.inner.lock().unwrap();
        let ClientInner { keys, groups } = &mut *g;
        ensure_group_loaded(&keys.provider, groups, &group_id)?;
        let group = groups
            .get_mut(&group_id)
            .ok_or_else(|| MlsError::General("group not found".into()))?;
        group
            .clear_pending_commit(keys.provider.storage())
            .map_err(to_err)?;

        Ok(())
    }

    pub fn clear_pending_proposals(&self, group_id: Vec<u8>) -> Result<(), MlsError> {
        let mut g = self.inner.lock().unwrap();
        let ClientInner { keys, groups } = &mut *g;
        ensure_group_loaded(&keys.provider, groups, &group_id)?;
        let group = groups
            .get_mut(&group_id)
            .ok_or_else(|| MlsError::General("group not found".into()))?;
        group
            .clear_pending_proposals(keys.provider.storage())
            .map_err(to_err)?;
        Ok(())
    }

    pub fn join_from_welcome(&self, welcome_bytes: Vec<u8>) -> Result<Vec<u8>, MlsError> {
        let mut g = self.inner.lock().unwrap();

        let welcome = MlsMessageIn::tls_deserialize_exact_bytes(&welcome_bytes).map_err(to_err)?;
        let welcome = match welcome.extract() {
            MlsMessageBodyIn::Welcome(w) => w,
            _ => return Err(MlsError::General("expected Welcome".into())),
        };

        let cfg = MlsGroupJoinConfig::builder()
            .use_ratchet_tree_extension(true)
            .build();
        let group = StagedWelcome::new_from_welcome(&g.keys.provider, &cfg, welcome, None)
            .and_then(|sw| sw.into_group(&g.keys.provider))
            .map_err(to_err)?;

        let id = group.group_id().as_slice().to_vec();
        g.groups.insert(id.clone(), group);
        Ok(id)
    }

    pub fn encrypt_message(
        &self,
        group_id: Vec<u8>,
        plaintext: Vec<u8>,
    ) -> Result<Vec<u8>, MlsError> {
        let mut g = self.inner.lock().unwrap();
        let ClientInner { keys, groups } = &mut *g;
        ensure_group_loaded(&keys.provider, groups, &group_id)?;

        let group = groups
            .get_mut(&group_id)
            .ok_or_else(|| MlsError::General("group not found".into()))?;

        let msg = group
            .create_message(&keys.provider, &keys.signer, &plaintext)
            .map_err(to_err)?;

        msg.tls_serialize_detached().map_err(to_err)
    }

    pub fn process_message(
        &self,
        group_id: Vec<u8>,
        msg_bytes: Vec<u8>,
    ) -> Result<IncomingMessage, MlsError> {
        let mut g = self.inner.lock().unwrap();
        let ClientInner { keys, groups } = &mut *g;
        ensure_group_loaded(&keys.provider, groups, &group_id)?;

        let msg = MlsMessageIn::tls_deserialize_exact_bytes(&msg_bytes)
            .map_err(to_err)?
            .try_into_protocol_message()
            .map_err(|_| MlsError::General("not a protocol message".into()))?;

        let group = groups
            .get_mut(&group_id)
            .ok_or_else(|| MlsError::General("group not found".into()))?;

        let _epoch_before = group.epoch().as_u64();

        let processed = group.process_message(&keys.provider, msg).map_err(to_err)?;

        match processed.into_content() {
            ProcessedMessageContent::ApplicationMessage(app) => Ok(IncomingMessage::Application {
                data: app.into_bytes(),
            }),
            ProcessedMessageContent::StagedCommitMessage(commit) => {
                group
                    .merge_staged_commit(&keys.provider, *commit)
                    .map_err(to_err)?;
                Ok(IncomingMessage::CommitApplied)
            }
            ProcessedMessageContent::ProposalMessage(_) => {
                let _ = group.clear_pending_proposals(keys.provider.storage());
                Ok(IncomingMessage::Proposal)
            }
            _ => Ok(IncomingMessage::Other),
        }
    }

    pub fn member_count(&self, group_id: Vec<u8>) -> Result<u64, MlsError> {
        let mut g = self.inner.lock().unwrap();
        let ClientInner { keys, groups } = &mut *g;
        ensure_group_loaded(&keys.provider, groups, &group_id)?;
        let group = groups
            .get(&group_id)
            .ok_or_else(|| MlsError::General("group not found".into()))?;
        Ok(group.members().count() as u64)
    }

    pub fn get_group_members_signature_keys(
        &self,
        group_id: Vec<u8>,
    ) -> Result<Vec<Vec<u8>>, MlsError> {
        let mut g = self.inner.lock().unwrap();
        let ClientInner { keys, groups } = &mut *g;
        ensure_group_loaded(&keys.provider, groups, &group_id)?;
        let group = groups
            .get(&group_id)
            .ok_or_else(|| MlsError::General("group not found".into()))?;

        let sig_keys: Vec<Vec<u8>> = group.members().map(|m| m.signature_key.to_vec()).collect();
        Ok(sig_keys)
    }

    pub fn get_group_epoch(&self, group_id: Vec<u8>) -> Result<u64, MlsError> {
        let mut g = self.inner.lock().unwrap();
        let ClientInner { keys, groups } = &mut *g;
        ensure_group_loaded(&keys.provider, groups, &group_id)?;
        let group = groups
            .get(&group_id)
            .ok_or_else(|| MlsError::General("group not found".into()))?;
        Ok(group.epoch().as_u64())
    }

    pub fn export_identity_key_pub(&self) -> Result<Vec<u8>, MlsError> {
        let g = self.inner.lock().unwrap();
        Ok(g.keys.signer.to_public_vec())
    }

    pub fn export_init_key_pub(&self) -> Result<Vec<u8>, MlsError> {
        self.generate_key_package()
    }

    pub fn export_credential_data(&self) -> Result<Vec<u8>, MlsError> {
        let g = self.inner.lock().unwrap();
        g.keys
            .credential_with_key
            .credential
            .tls_serialize_detached()
            .map_err(to_err)
    }

    pub fn leave_group(&self, group_id: Vec<u8>) -> Result<Vec<u8>, MlsError> {
        let mut g = self.inner.lock().unwrap();
        let ClientInner { keys, groups } = &mut *g;

        ensure_group_loaded(&keys.provider, groups, &group_id)?;

        let group = groups
            .get_mut(&group_id)
            .ok_or_else(|| MlsError::General("group not found".into()))?;

        let commit = group
            .leave_group(&keys.provider, &keys.signer)
            .map_err(to_err)?;

        let serialized = commit.tls_serialize_detached().map_err(to_err)?;

        if let Some(mut group) = groups.remove(&group_id) {
            let _ = group.delete(keys.provider.storage());
        }

        Ok(serialized)
    }

    pub fn delete_group(&self, group_id: Vec<u8>) -> Result<(), MlsError> {
        let mut g = self.inner.lock().unwrap();
        let ClientInner { keys, groups } = &mut *g;

        let _ = ensure_group_loaded(&keys.provider, groups, &group_id);

        if let Some(mut group) = groups.remove(&group_id) {
            group.delete(keys.provider.storage()).map_err(to_err)?;
        }

        Ok(())
    }

    pub fn remove_member_by_identity(
        &self,
        group_id: Vec<u8>,
        identity: String,
    ) -> Result<Vec<u8>, MlsError> {
        let mut g = self.inner.lock().unwrap();
        let ClientInner { keys, groups } = &mut *g;

        ensure_group_loaded(&keys.provider, groups, &group_id)?;

        let group = groups
            .get_mut(&group_id)
            .ok_or_else(|| MlsError::General("group not found".into()))?;

        let _ = group.clear_pending_proposals(keys.provider.storage());
        let _ = group.clear_pending_commit(keys.provider.storage());

        let target_identity = identity.as_bytes();

        let leaf_index = group
            .members()
            .find(|m| m.credential.serialized_content() == target_identity)
            .map(|m| m.index)
            .ok_or_else(|| {
                MlsError::General(format!(
                    "member with identity '{}' not found in group",
                    identity
                ))
            })?;

        let (commit, _, _) = group
            .remove_members(&keys.provider, &keys.signer, &[leaf_index])
            .map_err(to_err)?;

        let serialized = commit.tls_serialize_detached().map_err(to_err)?;
        Ok(serialized)
    }

    pub fn remove_member_by_signature_key(
        &self,
        group_id: Vec<u8>,
        signature_key: Vec<u8>,
    ) -> Result<Vec<u8>, MlsError> {
        let mut g = self.inner.lock().unwrap();
        let ClientInner { keys, groups } = &mut *g;

        ensure_group_loaded(&keys.provider, groups, &group_id)?;

        let group = groups
            .get_mut(&group_id)
            .ok_or_else(|| MlsError::General("group not found".into()))?;

        let _ = group.clear_pending_proposals(keys.provider.storage());
        let _ = group.clear_pending_commit(keys.provider.storage());

        let leaf_index = group
            .members()
            .find(|m| m.signature_key == signature_key)
            .map(|m| m.index)
            .ok_or_else(|| MlsError::General("member with signature key not found in group".into()))?;

        let (commit, _, _) = group
            .remove_members(&keys.provider, &keys.signer, &[leaf_index])
            .map_err(to_err)?;

        let serialized = commit.tls_serialize_detached().map_err(to_err)?;
        Ok(serialized)
    }
}
