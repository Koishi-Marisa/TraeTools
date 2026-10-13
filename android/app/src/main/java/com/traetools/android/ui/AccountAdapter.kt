package com.traetools.android.ui

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.traetools.android.R
import com.traetools.android.data.Account
import com.traetools.android.databinding.ItemAccountBinding

/** 账号列表适配器：支持重新登录、删除、就地编辑设备号。 */
class AccountAdapter(
    private val onRelogin: (Int) -> Unit,
    private val onDelete: (Int) -> Unit,
    private val onDeviceChanged: (Int, String) -> Unit
) : RecyclerView.Adapter<AccountAdapter.VH>() {

    private val items = mutableListOf<Account>()

    fun submit(list: List<Account>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemAccountBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val account = items[position]
        val binding = holder.binding
        val ctx = binding.root.context

        binding.tvIndex.text = ctx.getString(R.string.account_index, position + 1)
        binding.tvUid.text = if (account.uid.isNullOrEmpty()) {
            ctx.getString(R.string.account_uid_none)
        } else {
            ctx.getString(R.string.account_uid, account.uid)
        }
        binding.tvSessionState.text =
            ctx.getString(if (account.hasSession) R.string.account_has_session else R.string.account_no_session)

        // 复用 ViewHolder 时先移除旧监听，避免重复触发
        (binding.etDevice.getTag(R.id.etDevice) as? TextWatcher)?.let {
            binding.etDevice.removeTextChangedListener(it)
        }
        binding.etDevice.setText(account.deviceId)
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                onDeviceChanged(holder.bindingAdapterPosition, s?.toString().orEmpty())
            }
        }
        binding.etDevice.addTextChangedListener(watcher)
        binding.etDevice.setTag(R.id.etDevice, watcher)

        binding.btnRelogin.setOnClickListener { onRelogin(holder.bindingAdapterPosition) }
        binding.btnDelete.setOnClickListener { onDelete(holder.bindingAdapterPosition) }
    }

    class VH(val binding: ItemAccountBinding) : RecyclerView.ViewHolder(binding.root)
}